#!/usr/bin/env bats
# deploy/server 계약·kista-worker 불변식(1번 트랙 전제 — 위반 시 매매 배치 이중 실행) 정적 검사

D="$BATS_TEST_DIRNAME/../../deploy/server"

block() { awk -v s="  $1:" '$0 == s {on=1; next} on && /^  [a-z]/ {exit} on' "$D/docker-compose.yml"; }

@test "서비스는 kista-worker·kista-web 둘뿐" {
  [ "$(grep -E '^  kista-[a-z]+:$' "$D/docker-compose.yml" | tr -d ' ' | sort | tr '\n' ' ')" = "kista-web: kista-worker: " ]
}

@test "kista-worker: 단일 인스턴스·겹침 기동 설정 없음" {
  # 주석은 제외 — 금지 사유를 설명하는 주석이 이 단어들을 쓴다
  [ "$(grep -vE '^[[:space:]]*#' "$D/docker-compose.yml" | grep -cE 'replicas|scale:|start-first')" -eq 0 ]
  [ "$(block kista-worker | grep -vE '^[[:space:]]*#' | grep -c 'container_name: kista-worker')" -eq 1 ]
}

@test "kista-worker: stop_grace_period >= 200s + EPR 재발행 소유(kista-web은 없음)" {
  g=$(block kista-worker | sed -n 's/^ *stop_grace_period: *\([0-9]*\)s.*/\1/p')
  [ "$g" -ge 200 ]
  block kista-worker | grep -q 'SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART: "true"'
  [ "$(block kista-web | grep -vE '^[[:space:]]*#' | grep -c SPRING_MODULITH_EVENTS_REPUBLISH)" -eq 0 ]
}

@test "role별 이미지 변수 + roles 순서" {
  block kista-worker | grep -q 'image: ${KISTA_WORKER_IMAGE'
  block kista-web | grep -q 'image: ${KISTA_WEB_IMAGE'
  [ "$(grep -vE '^[[:space:]]*(#|$)' "$D/roles" | tr '\n' ' ')" = "kista-worker kista-web " ]
}

@test "헬스: Docker healthcheck는 2 role 모두 readiness, Caddy health_uri는 liveness" {
  # 배포 게이트가 Docker healthy만 보므로 healthy = 준비 완료여야 한다(readiness 계약 파일 폐지)
  grep -A1 '^x-healthcheck:' "$D/docker-compose.yml" | grep -q 'actuator/health/readiness"'
  for r in kista-worker kista-web; do block "$r" | grep -q 'healthcheck: \*healthcheck'; done
  # readiness로 바꾸면 DB 순간 장애 때 upstream 전체가 빠져 502. health_uri는 kista-infra (upstream) snippet 인자로만 넘긴다
  n=$(grep -cE '^[[:space:]]*reverse_proxy ' "$D/caddy/kista-api.caddy")
  [ "$n" -gt 0 ]
  [ "$(grep -cE 'import upstream(-stop-first)? /actuator/health/liveness$' "$D/caddy/kista-api.caddy")" -eq "$n" ]
  # lb_try 대역: kista-worker(stop-first)는 5초 snippet, kista-web(blue/green)은 120초 snippet
  [ "$(grep -A2 'reverse_proxy kista-worker:' "$D/caddy/kista-api.caddy" | grep -c 'import upstream-stop-first ')" -eq "$(grep -c 'reverse_proxy kista-worker:' "$D/caddy/kista-api.caddy")" ]
  [ "$(grep -A2 'reverse_proxy kista-web:' "$D/caddy/kista-api.caddy" | grep -c 'import upstream ')" -eq "$(grep -c 'reverse_proxy kista-web:' "$D/caddy/kista-api.caddy")" ]
  [ "$(grep -vE '^[[:space:]]*#' "$D/caddy/kista-api.caddy" | grep -c 'health_uri')" -eq 0 ]
  # unhealthy 기반 재기동 금지 — DB 장애가 kista-worker 재기동으로 번진다
  [ "$(grep -vE '^[[:space:]]*#' "$D/docker-compose.yml" | grep -ci autoheal)" -eq 0 ]
}

@test "bluegreen: kista-web만, kista-worker 금지 + 대상 서비스는 container_name 없음" {
  [ "$(grep -vE '^[[:space:]]*(#|$)' "$D/bluegreen")" = kista-web ]
  [ "$(block kista-web | grep -vE '^[[:space:]]*#' | grep -c container_name)" -eq 0 ]
}

@test "KISTA_ROLE: kista-web=web, kista-worker=worker, SCHEDULER_ENABLED·APP_JAR·SERVER_PORT 없음" {
  block kista-web | grep -q 'KISTA_ROLE: web'
  block kista-worker | grep -q 'DB_POOL_MAX: "10"'
  block kista-worker | grep -q 'KISTA_ROLE: worker'
  [ "$(grep -vE '^[[:space:]]*#' "$D/docker-compose.yml" | grep -cE 'SCHEDULER_ENABLED|APP_JAR|SERVER_PORT')" -eq 0 ]
}
