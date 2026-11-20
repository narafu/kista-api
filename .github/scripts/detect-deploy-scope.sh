#!/usr/bin/env bash
# 변경 파일 경로 목록(stdin) → 배포 범위 플래그(stdout, key=value 한 줄씩)
#   verify    : 코드·테스트·빌드 입력이 바뀜 → 전체 테스트 실행
#   config    : compose·Caddy·reconcile 계약·hook이 바뀜 → 이미지 재빌드 없이 kista-infra가 config SHA로 재적용
#               (compose가 영향받는 role만 재생성, Caddy만이면 reload만)
#   web       : app.jar 산출물이 바뀜 → kista-web 이미지 교체
#   worker    : app.jar 산출물이 바뀜 → kista-worker 이미지 교체 (web과 같은 jar·이미지, KISTA_ROLE만 다름)
# jar가 하나라 main 소스·빌드 입력이 바뀌면 web·worker 둘 다 교체한다.
# 테스트 전용 경로는 jar에 들어가지 않으므로 verify만 켠다. 워크플로·배포 판정 스크립트·bats는 deploy-checks 잡이 매번 검증하므로 아무것도 켜지 않는다.
set -euo pipefail

verify=false
config=false
web=false
worker=false

while IFS= read -r f; do
  case "$f" in
    # 테스트 소스 — 산출물 무관, 검증만
    src/test/*|src/testFixtures/*|*/src/test/*|*/src/testFixtures/*)
      verify=true ;;
    # app.jar에 들어가는 main 소스·마이그레이션·빌드 입력 — 전부
    src/main/*|trading-core/src/main/*|shared/src/main/*|shared/build.gradle.kts|trading-core/build.gradle.kts|build.gradle.kts|settings.gradle.kts|gradle.properties|gradle/*|gradlew|gradlew.bat|lombok.config|Dockerfile|.dockerignore)
      verify=true; web=true; worker=true ;;
    # Caddy 라우팅 스니펫 — verify는 CaddyRoutingTest가 이 파일의 regex를 컨트롤러 경로와 대조하기 때문
    deploy/server/caddy/*)
      verify=true; config=true ;;
    # reconcile bundle·hook — 이미지 무관
    deploy/server/docker-compose.yml|deploy/server/roles|deploy/server/bluegreen|deploy/hooks/*)
      config=true ;;
  esac
done

echo "verify=$verify"
echo "config=$config"
echo "web=$web"
echo "worker=$worker"
