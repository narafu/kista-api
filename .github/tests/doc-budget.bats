#!/usr/bin/env bats
# .github/scripts/check-doc-budget.sh — 문서 크기 예산 검사

SCRIPT="$BATS_TEST_DIRNAME/../scripts/check-doc-budget.sh"

# 항상 로드 5파일을 작게 만든다
setup() {
  mkdir -p "$BATS_TEST_TMPDIR/docs/agents/modules/api"
  cd "$BATS_TEST_TMPDIR"
  for f in CLAUDE.md docs/agents/{constraints,architecture,testing,commands}.md; do echo x > "$f"; done
}

@test "실제 레포가 예산 안에 있다" {
  run "$SCRIPT" "$BATS_TEST_DIRNAME/../.."
  [ "$status" -eq 0 ]
}

@test "모듈 문서가 예산을 넘으면 실패한다" {
  head -c 5001 /dev/zero | tr '\0' a > docs/agents/modules/api/x.md
  run "$SCRIPT" "$BATS_TEST_TMPDIR"
  [ "$status" -eq 1 ]
  [[ "$output" == *"docs/agents/modules/api/x.md"* ]]
}

@test "항상 로드 합계가 예산을 넘으면 실패한다" {
  head -c 25997 /dev/zero | tr '\0' a > CLAUDE.md   # 나머지 4파일(각 2바이트)과 합쳐 26000 초과
  run "$SCRIPT" "$BATS_TEST_TMPDIR"
  [ "$status" -eq 1 ]
  [[ "$output" == *"always-loaded"* ]]
}
