#!/usr/bin/env bash
# 배포 직전 선후 판정 — 3개 role 중 하나라도 target보다 더 새 커밋이 실행 중이면 그 컨테이너명을 출력하고 exit 0, 없으면 exit 1.
# compose 파일·Caddy 스니펫은 role 공유라 옛 run(Re-run)이 덮어쓰면 다른 role의 새 설정까지 되돌린다.
# 생략해도 안전: 더 새 push의 changes 잡이 이 role의 실행 중 SHA부터 diff했으므로 이 run의 변경을 이미 포함한다.
#
# 사용: newer-running.sh <target-sha>   (stdin: "/kista-api ghcr.io/...:<sha>" 줄)
set -euo pipefail

target=$1
while read -r name image; do
  sha=${image##*:}
  if [ -n "$sha" ] && [ "$sha" != "$target" ] && git cat-file -e "${sha}^{commit}" 2>/dev/null \
     && git merge-base --is-ancestor "$target" "$sha"; then
    echo "${name#/} ${sha}"
    exit 0
  fi
done
exit 1
