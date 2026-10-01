#!/usr/bin/env bash
# role 1개 교체 — 서버에서 실행(_deploy-role.yml이 bin/<service>/로 업로드 후 호출).
# 입력(env): DEPLOY_PATH, SERVICE(kista-api|kista-scheduler|kista-trading), KISTA_API_IMAGE(SHA 태그), RUN_TAG(run 시도 식별자)
# 사전 업로드: docker-compose.yml.<service>, caddy/kista-api.caddy.<service>
set -euo pipefail
exec </dev/null   # 하위 명령이 stdin을 물어 멈추거나 입력을 삼키지 않도록

: "${DEPLOY_PATH:?}" "${SERVICE:?}" "${KISTA_API_IMAGE:?}" "${RUN_TAG:?}"
cd "${DEPLOY_PATH}"
mkdir -p rollback caddy

# deploy-api / deploy-scheduler / deploy-trading 잡이 같은 디렉토리에서 파일 교체·pull·up 하므로 직렬화
exec 200>"${DEPLOY_PATH}/.deploy.lock"
flock 200

# 기본값이 있어 누락돼도 부팅은 되지만 조용히 오동작하는 키(INTERNAL_API_TOKEN·CORS·ADMIN_KAKAO_IDS 등)까지 포함
for key in DB_URL DB_USERNAME DB_PASSWORD JWT_SIGNING_KEY AES_ENCRYPTION_KEY INTERNAL_API_TOKEN KAKAO_CLIENT_ID \
           CORS_ALLOWED_ORIGINS TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID API_DOMAIN ADMIN_KAKAO_IDS; do
  grep -q "^${key}=" .env || { echo "::error::필수 환경변수 누락: ${key}"; exit 1; }
done

caddy_container() {
  docker ps -q --filter label=com.docker.compose.project=kista-infra --filter label=com.docker.compose.service=caddy
}

# Caddy 라우팅 스니펫 교체 + reload — kista-infra Caddyfile이 caddy/*.caddy를 import한다.
# reload 실패(문법 오류 등) 시 caddy는 기존 설정을 계속 서빙하므로 파일만 되돌리고 배포를 중단한다.
# .prev는 매번 새로 만든다 — 남아 있던 다른 배포의 .prev로 원복하거나, 최초 배포에 잘못된 스니펫이 남는 것 방지
rm -f caddy/kista-api.caddy.prev
if [ -f caddy/kista-api.caddy ]; then cp -p caddy/kista-api.caddy caddy/kista-api.caddy.prev; fi
mv "caddy/kista-api.caddy.${SERVICE}" caddy/kista-api.caddy
CADDY=$(caddy_container)
if [ -n "$CADDY" ] && ! docker exec "$CADDY" caddy reload --config /etc/caddy/Caddyfile; then
  # 원복 대상이 없으면(최초 배포) 잘못된 스니펫을 지워 다음 caddy 재기동이 이 파일 때문에 실패하지 않게 한다
  if [ -f caddy/kista-api.caddy.prev ]; then mv caddy/kista-api.caddy.prev caddy/kista-api.caddy; else rm -f caddy/kista-api.caddy; fi
  echo "::error::Caddy reload 실패 — 스니펫 원복, 배포 중단"
  exit 1
fi

# 롤백 기록: 교체 직전 compose 파일·Caddy 스니펫·실행 이미지 + 이번에 설치한 스니펫(롤백 시 다른 role이 그사이 바꿨는지 판별용).
# /tmp가 아닌 배포 디렉토리에 둬 재부팅에도 유지
cp docker-compose.yml "rollback/${SERVICE}.compose.yml" 2>/dev/null || true
rm -f "rollback/${SERVICE}.caddy"
if [ -f caddy/kista-api.caddy.prev ]; then cp -p caddy/kista-api.caddy.prev "rollback/${SERVICE}.caddy"; fi
cp -p caddy/kista-api.caddy "rollback/${SERVICE}.caddy.installed"
docker inspect -f '{{.Config.Image}}' "${SERVICE}" > "rollback/${SERVICE}.image" 2>/dev/null || : > "rollback/${SERVICE}.image"
mv "docker-compose.yml.${SERVICE}" docker-compose.yml

# GHCR 패키지가 public이라 로그인 없이 pull — 예전 배포가 남긴 만료 토큰이 ~/.docker/config.json에 있으면
# 익명 대신 그걸로 인증해 'denied'가 나므로 매번 제거
docker logout ghcr.io >/dev/null 2>&1 || true
export KISTA_API_IMAGE
docker compose pull "${SERVICE}"

for net in shared_net data_net; do docker network create "$net" >/dev/null 2>&1 || true; done

# 이 시점부터 컨테이너가 교체되므로 헬스 게이트가 롤백 대상으로 인식하도록 run 식별자 기록
echo "${RUN_TAG}" > "rollback/${SERVICE}.run"
docker compose up -d --no-deps "${SERVICE}"
