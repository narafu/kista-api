#!/usr/bin/env bats
# deploy/server/bin/{deploy-role,health-gate}.sh — docker를 스텁으로 바꿔 분기(원복·롤백 판정)를 검증

BIN="$BATS_TEST_DIRNAME/../../deploy/server/bin"

setup() {
  export DEPLOY_PATH="$BATS_TEST_TMPDIR/opt"
  export SERVICE=kista-trading KISTA_API_IMAGE=ghcr.io/narafu/kista-api:new RUN_TAG=run-1
  export DOCKER_LOG="$BATS_TEST_TMPDIR/docker.log" PATH="$BATS_TEST_DIRNAME/stub:$PATH"
  export STUB_PREV_IMAGE=ghcr.io/narafu/kista-api:old STUB_CADDY_ID=caddy1 GATE_ATTEMPTS=2 GATE_INTERVAL=0
  mkdir -p "$DEPLOY_PATH/caddy" "$DEPLOY_PATH/rollback"
  : > "$DOCKER_LOG"
  for k in DB_URL DB_USERNAME DB_PASSWORD JWT_SIGNING_KEY AES_ENCRYPTION_KEY INTERNAL_API_TOKEN KAKAO_CLIENT_ID \
           CORS_ALLOWED_ORIGINS TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID API_DOMAIN ADMIN_KAKAO_IDS; do
    echo "$k=x" >> "$DEPLOY_PATH/.env"
  done
  echo old-compose > "$DEPLOY_PATH/docker-compose.yml"
  echo new-compose > "$DEPLOY_PATH/docker-compose.yml.$SERVICE"
  echo old-snippet > "$DEPLOY_PATH/caddy/kista-api.caddy"
  echo new-snippet > "$DEPLOY_PATH/caddy/kista-api.caddy.$SERVICE"
}

@test "deploy-role: 롤백 기록을 남기고 compose·스니펫을 교체한 뒤 up" {
  run bash "$BIN/deploy-role.sh"
  [ "$status" -eq 0 ]
  [ "$(cat "$DEPLOY_PATH/docker-compose.yml")" = new-compose ]
  [ "$(cat "$DEPLOY_PATH/caddy/kista-api.caddy")" = new-snippet ]
  [ "$(cat "$DEPLOY_PATH/rollback/$SERVICE.compose.yml")" = old-compose ]
  [ "$(cat "$DEPLOY_PATH/rollback/$SERVICE.caddy")" = old-snippet ]
  [ "$(cat "$DEPLOY_PATH/rollback/$SERVICE.caddy.installed")" = new-snippet ]
  [ "$(cat "$DEPLOY_PATH/rollback/$SERVICE.image")" = "$STUB_PREV_IMAGE" ]
  [ "$(cat "$DEPLOY_PATH/rollback/$SERVICE.run")" = run-1 ]
  grep -q "docker logout ghcr.io" "$DOCKER_LOG"
  grep -q "docker compose up -d --no-deps $SERVICE" "$DOCKER_LOG"
}

@test "deploy-role: 필수 환경변수 누락이면 아무것도 교체하지 않고 실패" {
  sed -i '/^ADMIN_KAKAO_IDS=/d' "$DEPLOY_PATH/.env"
  run bash "$BIN/deploy-role.sh"
  [ "$status" -ne 0 ]
  [[ "$output" == *"ADMIN_KAKAO_IDS"* ]]
  [ "$(cat "$DEPLOY_PATH/caddy/kista-api.caddy")" = old-snippet ]
  [ ! -s "$DOCKER_LOG" ]
}

@test "deploy-role: caddy reload 실패면 스니펫 원복·run 기록 없이 중단" {
  export STUB_CADDY_RELOAD_RC=1
  run bash "$BIN/deploy-role.sh"
  [ "$status" -ne 0 ]
  [ "$(cat "$DEPLOY_PATH/caddy/kista-api.caddy")" = old-snippet ]
  [ ! -e "$DEPLOY_PATH/rollback/$SERVICE.run" ]
  ! grep -q "compose up" "$DOCKER_LOG"
}

@test "deploy-role: 최초 배포(기존 스니펫 없음)에서 reload 실패면 잘못된 스니펫을 남기지 않음" {
  rm "$DEPLOY_PATH/caddy/kista-api.caddy"
  export STUB_CADDY_RELOAD_RC=1
  run bash "$BIN/deploy-role.sh"
  [ "$status" -ne 0 ]
  [ ! -e "$DEPLOY_PATH/caddy/kista-api.caddy" ]
}

gate_after_deploy() {
  bash "$BIN/deploy-role.sh" >/dev/null
  : > "$DOCKER_LOG"
}

@test "health-gate: 다른 run의 기록이면 판정·롤백 없이 실패" {
  echo other-run > "$DEPLOY_PATH/rollback/$SERVICE.run"
  run bash "$BIN/health-gate.sh"
  [ "$status" -ne 0 ]
  ! grep -q "compose" "$DOCKER_LOG"
}

@test "health-gate: healthy + readiness UP이면 통과하고 자기 레포 이미지만 정리" {
  gate_after_deploy
  run bash "$BIN/health-gate.sh"
  [ "$status" -eq 0 ]
  grep -q "docker image ls --format {{.Repository}}:{{.Tag}} ghcr.io/narafu/kista-api" "$DOCKER_LOG"
  ! grep -q "prune -a" "$DOCKER_LOG"
  ! grep -q "compose" "$DOCKER_LOG"
}

@test "health-gate: readiness 미충족이 계속되면 이전 이미지·compose로 롤백하고 스니펫 원복" {
  gate_after_deploy
  export STUB_READY_RC=1
  run bash "$BIN/health-gate.sh"
  [ "$status" -ne 0 ]
  grep -q "docker compose -p opt --project-directory $DEPLOY_PATH -f rollback/$SERVICE.compose.yml up -d --no-deps $SERVICE" "$DOCKER_LOG"
  [ "$(cat "$DEPLOY_PATH/caddy/kista-api.caddy")" = old-snippet ]
  grep -q "docker exec caddy1 caddy reload" "$DOCKER_LOG"
}

@test "health-gate: 그사이 다른 role이 스니펫을 바꿨으면 원복하지 않음" {
  gate_after_deploy
  echo other-role-snippet > "$DEPLOY_PATH/caddy/kista-api.caddy"
  export STUB_HEALTH=unhealthy
  run bash "$BIN/health-gate.sh"
  [ "$status" -ne 0 ]
  grep -q "compose .* up -d --no-deps $SERVICE" "$DOCKER_LOG"
  [ "$(cat "$DEPLOY_PATH/caddy/kista-api.caddy")" = other-role-snippet ]
}

@test "health-gate: 이전 이미지 정보가 없으면 롤백하지 않고 실패" {
  export STUB_PREV_IMAGE=""
  gate_after_deploy
  export STUB_HEALTH=unhealthy
  run bash "$BIN/health-gate.sh"
  [ "$status" -ne 0 ]
  [[ "$output" == *"수동 복구 필요"* ]]
  ! grep -q "compose" "$DOCKER_LOG"
}

@test "trading-locks: 조회 결과를 그대로 출력하고 실패는 non-zero" {
  export STUB_LOCKS=trading-open
  run bash "$BIN/trading-locks.sh"
  [ "$status" -eq 0 ]
  [ "$output" = trading-open ]
  export STUB_PSQL_RC=2
  run bash "$BIN/trading-locks.sh"
  [ "$status" -ne 0 ]
}
