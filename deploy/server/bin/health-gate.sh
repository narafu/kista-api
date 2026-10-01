#!/usr/bin/env bash
# 헬스 게이트 + 자동 롤백 — 서버에서 실행(deploy-role.sh 다음, 실패해도 호출됨).
# 입력(env): DEPLOY_PATH, SERVICE, KISTA_API_IMAGE, RUN_TAG, GATE_ATTEMPTS(기본 30), GATE_INTERVAL(초, 기본 10)
# 이번 run이 컨테이너 교체를 시작한 경우(rollback/<service>.run == RUN_TAG)에만 판정·롤백한다 — 다른 run의 기록으로 롤백 방지
set -euo pipefail
exec </dev/null

: "${DEPLOY_PATH:?}" "${SERVICE:?}" "${KISTA_API_IMAGE:?}" "${RUN_TAG:?}"
attempts=${GATE_ATTEMPTS:-30}
interval=${GATE_INTERVAL:-10}
cd "${DEPLOY_PATH}"
export KISTA_API_IMAGE   # docker compose가 image: ${KISTA_API_IMAGE:?...}를 interpolate하려면 필요

if [ "$(cat "rollback/${SERVICE}.run" 2>/dev/null)" != "${RUN_TAG}" ]; then
  echo "✗ 이번 run에서 ${SERVICE} 컨테이너 교체가 시작되지 않음 — 게이트·롤백 생략"
  exit 1
fi

caddy_container() {
  docker ps -q --filter label=com.docker.compose.project=kista-infra --filter label=com.docker.compose.service=caddy
}

# 이 레포 이미지 중 컨테이너가 안 쓰는 태그만 정리(사용 중이면 rmi가 거부) + dangling 레이어.
# prune -f(dangling만)로는 SHA 태그가 무한 누적되고, prune -a는 다른 레포가 막 pull한 이미지까지 지울 수 있다.
# 배포 락 안에서 — 겹친 다른 run의 잡이 pull만 하고 아직 up 전인 이미지를 지우지 않도록. 롤백·수동 복구는 GHCR(public)에서 다시 pull
cleanup_images() {
  (
    flock 200
    docker image ls --format '{{.Repository}}:{{.Tag}}' ghcr.io/narafu/kista-api | xargs -r docker rmi >/dev/null 2>&1 || true
    docker image prune -f >/dev/null || true
  ) 200>"${DEPLOY_PATH}/.deploy.lock"
}

# 통과 조건: Docker 헬스(liveness) healthy + readiness(readinessState·db·redis) UP.
# liveness만으로는 DB/Redis 연결 불능 상태도 통과하므로 readiness를 함께 본다(그룹 구성은 application.yml)
echo "헬스 게이트 시작 — ${SERVICE} (최대 $((attempts * interval))초)..."
for i in $(seq 1 "$attempts"); do
  status=$(docker inspect --format '{{.State.Health.Status}}' "${SERVICE}" 2>/dev/null || echo "unknown")
  if [ "$status" = "healthy" ]; then
    if docker exec "${SERVICE}" wget -qO- http://localhost:8080/actuator/health/readiness >/dev/null 2>&1; then
      echo "✓ ${SERVICE} liveness·readiness 통과 (${i}회 시도)"
      cleanup_images
      exit 0
    fi
    status="healthy, readiness 미충족"
  fi
  if [ "$status" = "unhealthy" ]; then
    echo "✗ ${SERVICE} 헬스체크 실패 (unhealthy, ${i}회)"
    break
  fi
  echo "  대기 중... (${i}/${attempts}, 상태: ${status})"
  sleep "$interval"
done

prev_image=$(cat "rollback/${SERVICE}.image" 2>/dev/null || echo "")
if [ -z "$prev_image" ]; then
  echo "✗ ${SERVICE} 헬스 게이트 실패 — 이전 이미지 정보 없음, 수동 복구 필요"
  exit 1
fi

# 이미지뿐 아니라 compose 파일(환경변수·JAVA_OPTS·mem_limit)과 Caddy 스니펫까지 교체 직전 상태로 되돌린다
compose_prev="rollback/${SERVICE}.compose.yml"
[ -f "$compose_prev" ] || compose_prev=docker-compose.yml
echo "✗ ${SERVICE} 헬스 게이트 실패 — 롤백: ${prev_image} (${compose_prev})"
(
  flock 200
  KISTA_API_IMAGE="$prev_image" docker compose -p "$(basename "${DEPLOY_PATH}")" \
    --project-directory "${DEPLOY_PATH}" -f "$compose_prev" up -d --no-deps "${SERVICE}"
  # 스니펫 원복 — 롤백한 role이 새 라우팅의 경로를 아직 모를 수 있다. 단 그사이 다른 role이 스니펫을 바꿨으면(설치본과 다르면)
  # 그 role의 라우팅을 덮지 않도록 건너뛴다. 원복 reload 실패는 기존 설정 유지라 무시
  if [ -f "rollback/${SERVICE}.caddy" ] && cmp -s "rollback/${SERVICE}.caddy.installed" caddy/kista-api.caddy \
     && ! cmp -s "rollback/${SERVICE}.caddy" caddy/kista-api.caddy; then
    cp -p "rollback/${SERVICE}.caddy" caddy/kista-api.caddy
    caddy=$(caddy_container)
    if [ -n "$caddy" ]; then docker exec "$caddy" caddy reload --config /etc/caddy/Caddyfile || true; fi
  fi
) 200>"${DEPLOY_PATH}/.deploy.lock"
exit 1
