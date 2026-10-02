#!/usr/bin/env bash
# 배포 계획 — 기준점은 infra state/kista-api.yml(마지막 서버 적용 성공 상태). 직전 push 기준이면 실패·생략된 커밋을 영영 놓친다.
# 사용: plan-deploy.sh <target-sha>   (stdin: state 파일 내용)
# 출력: verify= build= deploy= config=<sha> roles=<role=sha ...>  — payload는 항상 앱 전체 상태(바뀐 것만 target)
#   - state SHA가 히스토리에 없음 → target + verify(판정 불가는 배포)
#   - target이 state SHA의 조상(옛 run 재실행) → state 유지
#   - 그 외 → state..target diff를 detect-deploy-scope.sh로 분류
set -euo pipefail

target=$1
scope_script="$(dirname "${BASH_SOURCE[0]}")/detect-deploy-scope.sh"
state=$(cat)
old_config=$(sed -n 's/^config: *\([0-9a-f]*\)$/\1/p' <<<"$state")
mapfile -t pairs < <(sed -n 's/^  \([a-z][a-z-]*\): *\([0-9a-f]*\)$/\1=\2/p' <<<"$state")
if [ -z "$old_config" ] || [ "${#pairs[@]}" -eq 0 ]; then
  echo "::error::state 파일 형식 오류·비어 있음" >&2
  exit 1
fi

# pick은 $(...) 서브셸에서 돌아 변수 대입이 부모에 전파되지 않으므로 verify는 파일로 모은다
verify_file=$(mktemp)
trap 'rm -f "$verify_file"' EXIT
echo false > "$verify_file"
# $1 기준 SHA, $2 판정할 플래그 키(config|api|scheduler|trading) → target 또는 기준 SHA 출력
pick() {
  local base=$1 key=$2 flags
  if ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo true > "$verify_file"; echo "$target"; return
  fi
  if git merge-base --is-ancestor "$target" "$base"; then echo "$base"; return; fi
  flags=$(git diff --name-only "$base" "$target" | bash "$scope_script")
  if grep -q '^verify=true' <<<"$flags"; then echo true > "$verify_file"; fi
  if grep -q "^${key}=true" <<<"$flags"; then echo "$target"; else echo "$base"; fi
}

config=$(pick "$old_config" config)
roles=() build=false deploy=false
[ "$config" = "$old_config" ] || deploy=true
for pair in "${pairs[@]}"; do
  role=${pair%%=*} sha=${pair#*=}
  new=$(pick "$sha" "${role#kista-}")
  echo "$role: state=$sha → $new" >&2
  roles+=("$role=$new")
  if [ "$new" != "$sha" ]; then build=true; deploy=true; fi
done
echo "verify=$(cat "$verify_file")"
echo "build=$build"
echo "deploy=$deploy"
echo "config=$config"
echo "roles=${roles[*]}"
