#!/usr/bin/env bats
# .github/scripts/epr-orphans.sh — 미완료 EPR row가 배포 소스에서 resolve되는지 검사

SCRIPT="$BATS_TEST_DIRNAME/../scripts/epr-orphans.sh"

setup() {
  cd "$BATS_TEST_TMPDIR" && git init -q repo && cd repo
  mkdir -p trading-core/src/main/java/com/kista/trading/event trading-core/src/main/java/com/kista/tradingnotify
  echo "public record CycleEndedEvent() {}" > trading-core/src/main/java/com/kista/trading/event/CycleEndedEvent.java
  printf 'class CycleEndedNotifier {\n    void onCycleEnded(CycleEndedEvent e) {}\n}\n' \
    > trading-core/src/main/java/com/kista/tradingnotify/CycleEndedNotifier.java
  git add -A
}

check() { printf '%s\n' "$1" | bash "$SCRIPT"; }

@test "이벤트·리스너·메서드가 모두 있으면 통과, 미완료 row가 없어도 통과" {
  run check "com.kista.trading.event.CycleEndedEvent|com.kista.tradingnotify.CycleEndedNotifier.onCycleEnded(com.kista.trading.event.CycleEndedEvent)"
  [ "$status" -eq 0 ]
  run check ""
  [ "$status" -eq 0 ]
}

@test "리스너 패키지 이동(옛 FQCN)은 고아로 차단" {
  run check "com.kista.trading.event.CycleEndedEvent|com.kista.trading.notify.CycleEndedNotifier.onCycleEnded(com.kista.trading.event.CycleEndedEvent)"
  [ "$status" -eq 1 ]
  [[ "$output" == *"리스너 com.kista.trading.notify.CycleEndedNotifier"* ]]
}

@test "이벤트 클래스 이동·리스너 메서드 개명도 차단" {
  run check "com.kista.sharedkernel.CycleEndedEvent|com.kista.tradingnotify.CycleEndedNotifier.onCycleEnded(com.kista.sharedkernel.CycleEndedEvent)"
  [ "$status" -eq 1 ] && [[ "$output" == *"이벤트 com.kista.sharedkernel.CycleEndedEvent"* ]]
  run check "com.kista.trading.event.CycleEndedEvent|com.kista.tradingnotify.CycleEndedNotifier.onEnded(com.kista.trading.event.CycleEndedEvent)"
  [ "$status" -eq 1 ] && [[ "$output" == *"리스너 메서드"* ]]
}

@test "중첩 클래스 이벤트는 바깥 파일로 찾는다" {
  run check 'com.kista.trading.event.CycleEndedEvent$Inner|'
  [ "$status" -eq 0 ]
}
