#!/usr/bin/env bash
# kista-infra Reconcile App run을 request_id로 찾아 완료까지 대기 — 앱 커밋의 초록불 = 서버 적용·헬스 게이트 통과.
# 사용: wait-reconcile.sh <request_id>   env: GH_TOKEN, INFRA_REPO(기본 narafu/kista-infra), FIND_ATTEMPTS(24), FIND_INTERVAL(초, 5)
#   RUN_ID: 이미 찾은 run이면 탐색 생략(토큰 재발급 후 이어서 대기할 때)
#   WATCH_TIMEOUT: 이 시간(timeout 형식, 예 50m) 안에 안 끝나면 run_id를 GITHUB_OUTPUT에 남기고 성공 종료 —
#     앱 설치 토큰이 1시간이면 만료돼 그 이상 watch하면 401로 실패하므로, 워크플로가 토큰을 재발급해 이어서 대기한다
# cancelled이면서 같은 앱의 더 새 run이 있으면 대기 자리를 대체당한 것(payload가 앱 전체 상태라 손실 없음) → 생략으로 성공 처리
set -euo pipefail

req=$1
repo=${INFRA_REPO:-narafu/kista-infra}
id=${RUN_ID:-}
for ((i = 1; i <= ${FIND_ATTEMPTS:-24}; i++)); do
  if [ -n "$id" ]; then break; fi
  id=$(gh run list -R "$repo" --workflow reconcile.yml -L 30 --json databaseId,displayTitle \
    --jq "[.[] | select(.displayTitle | endswith(\" ${req}\"))][0].databaseId // empty")
  [ -n "$id" ] || sleep "${FIND_INTERVAL:-5}"
done
[ -n "$id" ] || { echo "::error::kista-infra reconcile run(${req})을 찾지 못함 — dispatch 토큰·이벤트 타입 확인"; exit 1; }

echo "kista-infra run: https://github.com/${repo}/actions/runs/${id}"
watch=()
if [ -n "${WATCH_TIMEOUT:-}" ]; then watch=(timeout "$WATCH_TIMEOUT"); fi
"${watch[@]}" gh run watch "$id" -R "$repo" --exit-status >/dev/null && exit 0
# timeout 종료 코드는 구현마다 달라(GNU 124, busybox 143) 코드 대신 run 상태로 판정 — 조회 실패(빈값)는 이어받지 않음
if [ -n "${WATCH_TIMEOUT:-}" ] && [[ "$(gh run view "$id" -R "$repo" --json status -q .status || true)" =~ ^(queued|in_progress|waiting|pending|requested)$ ]]; then
  echo "run_id=${id}" >> "${GITHUB_OUTPUT:?}"
  echo "::notice::${WATCH_TIMEOUT} 안에 끝나지 않음 — 토큰 재발급 후 이어서 대기"
  exit 0
fi
conclusion=$(gh run view "$id" -R "$repo" --json conclusion -q .conclusion)
# cancelled는 대체(대기 자리를 더 새 요청이 차지) 외에 잡 타임아웃·수동 취소도 낸다 — 같은 앱의 더 새 run이 실제로 있을 때만 생략
if [ "$conclusion" = cancelled ]; then
  app=${req%-*-*}   # request_id = <app>-<run_id>-<attempt>
  newer=$(gh run list -R "$repo" --workflow reconcile.yml -L 30 --json databaseId,displayTitle \
    --jq "[.[] | select(.databaseId > ${id} and (.displayTitle | startswith(\"reconcile ${app} \")))] | length")
  if [ "${newer:-0}" -gt 0 ]; then
    echo "::notice::더 새 배포 요청이 이 요청을 대체함 — 생략"
    exit 0
  fi
fi
echo "::error::kista-infra reconcile ${conclusion} — 위 링크에서 원인 확인(서버는 직전 release로 롤백됐거나 수동 복구 필요)"
exit 1
