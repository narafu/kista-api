#!/usr/bin/env bash
# 로컬 dev 시드 — dev 유저(00000000-...-0001)에만 적용, 재실행 가능
# 전제: root(8080)·trading-core(8081) local 프로파일 기동, docker compose postgres 기동,
#       trading-core application-local.yml에 toss.admin-client-id/secret 설정(MOCK 시세 피드용)
# 1) MOCK 계좌·ACTIVE 전략은 API로 등록(계좌 컬럼 AES 암호화 때문에 SQL 직접 삽입 불가)
# 2) KIS [시드] 계좌(MOCK 암호문 복사)·가계부·equity-curve 스냅샷·관리자 주문 이력·사이클 성과 목록용 사이클은 dev-seed.sql
# 3) 벤치마크(kista_ref) 전역 데이터는 실제 수집기 트리거(가짜 행 미삽입 — kbland 2종 + ETF 지수 종가)
set -euo pipefail

# Windows jq.exe는 CRLF로 출력해 id 끝에 \r이 붙는다 — 모든 호출에서 제거
jq() { command jq "$@" | tr -d '\r'; }

API=${API:-http://localhost:8080}
TRADING=${TRADING:-http://localhost:8081}
# 레포 루트 compose의 postgres 컨테이너 — compose 프로젝트명과 무관하게 해석
PG_CONTAINER=${PG_CONTAINER:-$(docker compose -f "$(cd "$(dirname "$0")/../.." && pwd)/docker-compose.yml" ps -q postgres)}
[[ -n "$PG_CONTAINER" ]] || { echo "postgres 컨테이너 미기동: docker compose up -d postgres" >&2; exit 1; }
MOCK_NICKNAME='[시드] 모의계좌'
DIR=$(cd "$(dirname "$0")" && pwd)

TOKEN=$(curl -fsS -X POST "$API/api/auth/dev-token" | jq -r .accessToken)
ADMIN_TOKEN=$(curl -fsS -X POST "$API/api/auth/dev-admin-token" | jq -r .accessToken)
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')

# dev-token이 trading-core 사용자 복제본(trading.user_notify_profile) 동기화 이벤트를 발행한다 — 복제본이 없으면
# 전략 등록이 404("사용자를 찾을 수 없습니다"). Redis Stream 경유 비동기 반영이라 최대 10초 대기
DEV_USER=00000000-0000-0000-0000-000000000001
psql_val() { docker exec "$PG_CONTAINER" psql -Atq -U kista -d kistadb -c "$1"; }
for _ in {1..20}; do
  [[ $(psql_val "SELECT count(*) FROM trading.user_notify_profile WHERE user_id = '$DEV_USER'") == 1 ]] && break
  sleep 0.5
done
[[ $(psql_val "SELECT count(*) FROM trading.user_notify_profile WHERE user_id = '$DEV_USER'") == 1 ]] \
  || echo "trading-core 사용자 복제본 미반영 — 전략 등록이 404로 실패할 수 있음(trading-core 기동·Redis 확인)" >&2

# MOCK 계좌 — 닉네임으로 기존 계좌 재사용
ACCOUNT=$(curl -fsS "${AUTH[@]}" "$TRADING/api/accounts" \
  | jq -r --arg n "$MOCK_NICKNAME" '[.[] | select(.broker == "MOCK" and .nickname == $n)][0].id // empty')
if [[ -z "$ACCOUNT" ]]; then
  # 한글 닉네임은 stdin으로 보낸다 — Windows curl은 인자 문자열을 ANSI 코드페이지로 바꿔 UTF-8이 깨진다(400)
  ACCOUNT=$(jq -n --arg n "$MOCK_NICKNAME" '{nickname: $n, broker: "MOCK"}' \
    | curl -fsS -X POST "${AUTH[@]}" "$TRADING/api/accounts" --data-binary @- | jq -r .id)
fi
echo "MOCK 계좌: $ACCOUNT"

# 전략 등록 — 같은 type:ticker가 이미 있으면 건너뜀 (MOCK 어댑터는 계좌 내 ticker당 전략 1개를 가정)
EXISTING=$(curl -fsS "${AUTH[@]}" "$TRADING/api/accounts/$ACCOUNT/trading-cycles" | jq -r '.[] | .type + ":" + .ticker')
FAILED=0
register() {
  local key=$1 body=$2 resp
  if grep -qx "$key" <<<"$EXISTING"; then echo "전략 $key: 이미 존재"; return; fi
  resp=$(curl -sS -w '\n%{http_code}' -X POST "${AUTH[@]}" "$TRADING/api/accounts/$ACCOUNT/trading-cycles" -d "$body")
  if [[ ${resp##*$'\n'} == 201 ]]; then echo "전략 $key: 등록"; else echo "전략 $key: 실패 $resp" >&2; FAILED=1; fi
  [[ $resp == *BROKER_CREDENTIAL_INVALID* ]] && CREDENTIAL_INVALID=1
  return 0
}
CREDENTIAL_INVALID=0
register INFINITE:SOXL '{"type":"INFINITE","ticker":"SOXL","initialUsdDeposit":5000,"initialHoldings":40,"initialAvgPrice":28.15}'
register VR:TQQQ '{"type":"VR","ticker":"TQQQ","initialUsdDeposit":3000,"intervalWeeks":2,"bandWidth":15,"recurringAmount":0,"initialHoldings":30,"initialAvgPrice":70}'
# MOCK 시세(Toss 관리자 키)의 OAuth 403 "IP address not allowed"도 BROKER_CREDENTIAL_INVALID(422)로 매핑된다 — 키보다 허용 IP 누락이 흔하다
if (( CREDENTIAL_INVALID )); then
  echo "힌트: trading-core 로그에 'IP address not allowed'가 있으면 Toss 허용 IP에 현재 공인 IP($(curl -fsS --max-time 3 https://api.ipify.org || echo '조회 실패'))를 추가 — 키 문제가 아님" >&2
fi

docker exec -i "$PG_CONTAINER" psql -q -U kista -d kistadb < "$DIR/dev-seed.sql"
echo "SQL 시드 적용"

# 벤치마크 수집기 트리거 — 202 후 백그라운드 실행(root 로그 확인)
# 트리거 엔드포인트는 scheduler.enabled=true일 때만 등록되는데 root local은 false(텔레그램 중복 방지) —
# 404면 root를 SCHEDULER_ENABLED=true로 잠시 띄워 이 스크립트를 다시 실행한 뒤 원복한다(kista_ref 행이 있으면 생략 가능)
for path in kbland-housing-benchmark kbland-price-index market-index-prices; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $ADMIN_TOKEN" "$API/api/admin/scheduler/$path")
  case $code in
    202) echo "수집기 트리거: $path" ;;
    404) echo "수집기 트리거 생략: $path — root를 SCHEDULER_ENABLED=true로 기동해야 함" >&2 ;;
    *)   echo "수집기 트리거 실패: $path $code" >&2 ;;
  esac
done

exit $FAILED
