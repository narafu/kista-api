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

@test "cancelled + 같은 앱의 더 새 run이 있으면(대체됨) 생략으로 성공" {
  STUB_WATCH_RC=1 STUB_CONCLUSION=cancelled STUB_NEWER=1 run bash "$SCRIPT" kista-api-9-1
  [ "$status" -eq 0 ]
  [[ "$output" == *"대체"* ]]
  grep -q 'startswith("reconcile kista-api ")' "$GH_LOG"
}

@test "cancelled인데 더 새 run이 없으면(타임아웃·수동 취소) 실패" {
  STUB_WATCH_RC=1 STUB_CONCLUSION=cancelled STUB_NEWER=0 run bash "$SCRIPT" kista-api-9-1
  [ "$status" -eq 1 ]
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
