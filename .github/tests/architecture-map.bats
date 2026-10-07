#!/usr/bin/env bats
# docs/architecture-map.md "L0-1 배포 구성"이 deploy/server 실제 구성을 빠짐없이 담는지 대조 — 수기 그림 드리프트 방지
# kista-infra·kista-ui 소유 구성(caddy·postgres·redis·kista-ui)은 다른 레포라 여기서 검사하지 않는다

D="$BATS_TEST_DIRNAME/../../deploy/server"
MAP="$BATS_TEST_DIRNAME/../../docs/architecture-map.md"

section() { awk '/^## L0-1 /{on=1; next} on && /^## /{exit} on' "$MAP"; }
top_keys() { awk -v s="$1:" '$0 == s {on=1; next} on && /^[a-z]/ {exit} on && /^  [a-z_-]+:/ {sub(/^  /, ""); sub(/:.*/, ""); print}' "$D/docker-compose.yml"; }

assert_in_section() {
  local missing=() s
  s=$(section)
  [ -n "$s" ] || { echo "L0-1 절 없음"; return 1; }
  for k in "$@"; do grep -qF -- "$k" <<<"$s" || missing+=("$k"); done
  [ ${#missing[@]} -eq 0 ] || { echo "L0-1 배포 구성에 없음: ${missing[*]}"; return 1; }
}

@test "compose 서비스 전부가 배포 구성도에 있다" {
  mapfile -t svcs < <(top_keys services)
  [ ${#svcs[@]} -gt 0 ]
  assert_in_section "${svcs[@]}"
}

@test "compose 네트워크 전부가 배포 구성도에 있다" {
  mapfile -t nets < <(top_keys networks)
  [ ${#nets[@]} -gt 0 ]
  assert_in_section "${nets[@]}"
}

@test "Caddy matcher 전부가 배포 구성도에 있다" {
  mapfile -t ms < <(grep -oE '^[[:space:]]*@[a-z_-]+' "$D/caddy/kista-api.caddy" | sed 's/^[[:space:]]*//' | sort -u)
  [ ${#ms[@]} -gt 0 ]
  assert_in_section "${ms[@]}"
}

@test "blue/green 대상이 배포 구성도에 표기돼 있다" {
  s=$(section)
  for r in $(grep -vE '^[[:space:]]*(#|$)' "$D/bluegreen"); do
    grep -qE "$r · blue/green" <<<"$s" || { echo "blue/green 표기 없음: $r"; return 1; }
  done
}
