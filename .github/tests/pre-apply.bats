#!/usr/bin/env bats
# deploy/hooks/pre-apply.sh — remote 스텁으로 role별 스키마 조회·판정·fail-closed 검증

HOOK="$BATS_TEST_DIRNAME/../../deploy/hooks/pre-apply.sh"

setup() {
  cd "$BATS_TEST_TMPDIR" && git init -q repo && cd repo
  mkdir -p src/main/java/com/kista/x && echo 'class E {}' > src/main/java/com/kista/x/E.java
  git add -A && git -c user.email=t@t -c user.name=t commit -qm init
  SHA=$(git rev-parse HEAD)
  mkdir -p "$BATS_TEST_TMPDIR/bin"
  export REMOTE_LOG="$BATS_TEST_TMPDIR/remote.log" PATH="$BATS_TEST_TMPDIR/bin:$PATH"
  printf '#!/usr/bin/env bash\necho "$*" >> "$REMOTE_LOG"\necho "${STUB_PENDING:-}"\nexit "${STUB_REMOTE_RC:-0}"\n' > "$BATS_TEST_TMPDIR/bin/remote"
  chmod +x "$BATS_TEST_TMPDIR/bin/remote"
}

@test "kista-api만 바뀌면 조회 없이 통과" {
  HOOK_ROLES="kista-api=$SHA" run bash "$HOOK"
  [ "$status" -eq 0 ]
  [ ! -e "$REMOTE_LOG" ]
}

@test "trading·scheduler는 각자 스키마 조회, 고아 없으면 통과" {
  HOOK_ROLES="kista-trading=$SHA kista-scheduler=$SHA" STUB_PENDING="com.kista.x.E|" run bash "$HOOK"
  [ "$status" -eq 0 ]
  grep -q 'FROM trading.event_publication' "$REMOTE_LOG"
  grep -q 'FROM public.event_publication' "$REMOTE_LOG"
}

@test "고아가 있으면 차단" {
  HOOK_ROLES="kista-trading=$SHA" STUB_PENDING="com.kista.y.Gone|" run bash "$HOOK"
  [ "$status" -eq 1 ]
  [[ "$output" == *"런북"* ]]
}

@test "조회 실패는 fail-closed" {
  HOOK_ROLES="kista-scheduler=$SHA" STUB_REMOTE_RC=1 run bash "$HOOK"
  [ "$status" -eq 1 ]
}
