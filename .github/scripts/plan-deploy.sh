#!/usr/bin/env bash
# role별 배포 범위 판정 — 기준점은 직전 push가 아니라 서버에서 각 role이 "지금 실행 중인" 이미지 SHA.
# 직전 push 기준이면 가드 차단·헬스 롤백으로 배포가 빠진 커밋을 다음 push가 영영 놓친다(조용한 드리프트).
#
# 사용: plan-deploy.sh <target-sha>   (stdin: "docker inspect -f '{{.Name}} {{.Config.Image}}'" 출력 — "/kista-api ghcr.io/...:<sha>" 줄)
# 출력: api=… scheduler=… trading=… verify=… (key=value 한 줄씩)
#   - 실행 중 SHA 없음·히스토리에 없음 → 그 role true(판정 불가는 배포)
#   - 실행 중 SHA가 target 이상(조상이 target) → false(옛 run 재실행이 과거로 되돌리는 것 방지)
#   - 그 외 → 실행 중 SHA..target diff를 detect-deploy-scope.sh로 분류
set -euo pipefail

target=$1
scope_script="$(dirname "${BASH_SOURCE[0]}")/detect-deploy-scope.sh"

declare -A running
while read -r name image; do
  if [ -n "${name:-}" ]; then running[${name#/}]=${image##*:}; fi
done

verify=false
for role in api scheduler trading; do
  sha=${running[kista-$role]:-}
  if [ -n "$sha" ] && git cat-file -e "${sha}^{commit}" 2>/dev/null; then
    if git merge-base --is-ancestor "$target" "$sha"; then
      flags="verify=false"$'\n'"$role=false"
    else
      flags=$(git diff --name-only "$sha" "$target" | bash "$scope_script")
    fi
  else
    flags="verify=true"$'\n'"$role=true"
  fi
  echo "kista-$role: running=${sha:-none}" >&2
  grep "^$role=" <<<"$flags"
  if grep -q '^verify=true' <<<"$flags"; then verify=true; fi
done
echo "verify=$verify"
