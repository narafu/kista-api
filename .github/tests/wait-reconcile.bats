#!/usr/bin/env bats
# .github/scripts/wait-reconcile.sh — gh 스텁으로 성공·대체·실패·미발견

SCRIPT="$BATS_TEST_DIRNAME/../scripts/wait-reconcile.sh"

setup() {
  export GH_LOG="$BATS_TEST_TMPDIR/gh.log" PATH="$BATS_TEST_DIRNAME/stub:$PATH" FIND_ATTEMPTS=2 FIND_INTERVAL=0 STUB_RUN_ID=42
}

@test "성공" {
  run bash "$SCRIPT" kista-api-1-1
  [ "$status" -eq 0 ]
  grep -q 'kista-api-1-1' "$GH_LOG"
  [[ "$output" == *"actions/runs/42"* ]]
}

@test "cancelled(대체됨)는 생략으로 성공" {
  STUB_WATCH_RC=1 STUB_CONCLUSION=cancelled run bash "$SCRIPT" r
  [ "$status" -eq 0 ]
  [[ "$output" == *"대체"* ]]
}

@test "failure는 실패" {
  STUB_WATCH_RC=1 STUB_CONCLUSION=failure run bash "$SCRIPT" r
  [ "$status" -eq 1 ]
}

@test "run 미발견은 실패" {
  STUB_RUN_ID= run bash "$SCRIPT" r
  [ "$status" -eq 1 ]
  [ "$(grep -c 'run list' "$GH_LOG")" -eq 2 ]
}
