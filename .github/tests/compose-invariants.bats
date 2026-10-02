#!/usr/bin/env bats
# deploy/server 계약·kista-trading 불변식(1번 트랙 전제 — 위반 시 매매 배치 이중 실행) 정적 검사

D="$BATS_TEST_DIRNAME/../../deploy/server"

block() { awk -v s="  $1:" '$0 == s {on=1; next} on && /^  [a-z]/ {exit} on' "$D/docker-compose.yml"; }

@test "kista-trading: 단일 인스턴스·겹침 기동 설정 없음" {
  # 주석은 제외 — 금지 사유를 설명하는 주석이 이 단어들을 쓴다
  [ "$(grep -vE '^[[:space:]]*#' "$D/docker-compose.yml" | grep -cE 'replicas|scale:|start-first')" -eq 0 ]
}

@test "kista-trading: stop_grace_period >= 200s + EPR 재발행 소유" {
  g=$(block kista-trading | sed -n 's/^ *stop_grace_period: *\([0-9]*\)s.*/\1/p')
  [ "$g" -ge 200 ]
  block kista-trading | grep -q 'SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"'
}

@test "role별 이미지 변수 + roles 순서·readiness 계약" {
  block kista-trading | grep -q 'image: ${KISTA_TRADING_IMAGE'
  block kista-scheduler | grep -q 'image: ${KISTA_SCHEDULER_IMAGE'
  block kista-api | grep -q 'image: ${KISTA_API_IMAGE'
  [ "$(grep -vE '^[[:space:]]*(#|$)' "$D/roles" | tr '\n' ' ')" = "kista-trading kista-api kista-scheduler " ]
  [ "$(awk '{print $1}' "$D/readiness" | sort | tr '\n' ' ')" = "kista-api kista-scheduler kista-trading " ]
}
