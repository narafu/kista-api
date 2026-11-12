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

@test "role별 이미지 변수 + roles 순서" {
  block kista-trading | grep -q 'image: ${KISTA_TRADING_IMAGE'
  block kista-scheduler | grep -q 'image: ${KISTA_SCHEDULER_IMAGE'
  block kista-api | grep -q 'image: ${KISTA_API_IMAGE'
  [ "$(grep -vE '^[[:space:]]*(#|$)' "$D/roles" | tr '\n' ' ')" = "kista-trading kista-api kista-scheduler " ]
}

@test "헬스: Docker healthcheck는 3 role 모두 readiness, Caddy health_uri는 liveness" {
  # 배포 게이트가 Docker healthy만 보므로 healthy = 준비 완료여야 한다(readiness 계약 파일 폐지)
  grep -A1 '^x-healthcheck:' "$D/docker-compose.yml" | grep -q 'actuator/health/readiness"'
  for r in kista-trading kista-api kista-scheduler; do block "$r" | grep -q 'healthcheck: \*healthcheck'; done
  # readiness로 바꾸면 DB 순간 장애 때 upstream 전체가 빠져 502
  n=$(grep -c 'health_uri' "$D/caddy/kista-api.caddy")
  [ "$n" -gt 0 ]
  [ "$(grep -c 'health_uri /actuator/health/liveness$' "$D/caddy/kista-api.caddy")" -eq "$n" ]
  # unhealthy 기반 재기동 금지 — DB 장애가 kista-trading 재기동으로 번진다
  [ "$(grep -vE '^[[:space:]]*#' "$D/docker-compose.yml" | grep -ci autoheal)" -eq 0 ]
}

@test "bluegreen: kista-api만, kista-trading·kista-scheduler 금지 + 대상 서비스는 container_name 없음" {
  [ "$(grep -vE '^[[:space:]]*(#|$)' "$D/bluegreen")" = kista-api ]
  [ "$(block kista-api | grep -vE '^[[:space:]]*#' | grep -c container_name)" -eq 0 ]
}
