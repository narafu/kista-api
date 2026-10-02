#!/usr/bin/env bash
# 변경 파일 경로 목록(stdin) → 배포 범위 플래그(stdout, key=value 한 줄씩)
#   verify    : 코드·테스트·빌드 입력이 바뀜 → 전체 테스트 실행
#   config    : compose·Caddy·reconcile 계약·hook이 바뀜 → 이미지 재빌드 없이 kista-infra가 config SHA로 재적용
#               (compose가 영향받는 role만 재생성, Caddy만이면 reload만)
#   api       : app.jar 산출물이 바뀜 → kista-api 이미지 교체
#   scheduler : app.jar 산출물이 바뀜 → kista-scheduler 이미지 교체 (kista-api와 같은 jar, SCHEDULER_ENABLED=true만 다름)
#   trading   : trading-core.jar 산출물이 바뀜 → kista-trading 이미지 교체
# 스케쥴러 전용 빈(@ConditionalOnProperty scheduler.enabled — adapter/in/schedule/* 와 AdminSchedulerController)은
# kista-api role에 아예 등록되지 않으므로 그 파일만 바뀐 커밋은 scheduler만 교체한다.
# 테스트 전용 경로는 jar에 들어가지 않으므로 verify만 켠다. 워크플로·배포 판정 스크립트·bats는 deploy-checks 잡이 매번 검증하므로 아무것도 켜지 않는다.
set -euo pipefail

verify=false
config=false
api=false
scheduler=false
trading=false

while IFS= read -r f; do
  case "$f" in
    # 테스트 소스 — 산출물 무관, 검증만
    src/test/*|src/testFixtures/*|*/src/test/*|*/src/testFixtures/*)
      verify=true ;;
    # trading-core.jar 전용
    trading-core/src/main/*)
      verify=true; trading=true ;;
    # root 소유 스키마 변경 — root(api·scheduler)만 적용한다. trading은 자체 migration-trading(위 trading-core/src/main/*)을
    # 소유하고 root 테이블에 의존하지 않는다(서비스별 Flyway 이력 분리)
    src/main/resources/db/migration/*)
      verify=true; api=true; scheduler=true ;;
    # scheduler role에만 등록되는 빈 — kista-api에는 존재하지 않음
    src/main/java/*/adapter/in/schedule/*|src/main/java/com/kista/web/AdminSchedulerController.java)
      verify=true; scheduler=true ;;
    # 그 외 app.jar — 스케쥴러가 서비스·어댑터를 그대로 호출하므로 둘 다
    src/main/*)
      verify=true; api=true; scheduler=true ;;
    # Caddy 라우팅 스니펫 — verify는 CaddyRoutingTest가 이 파일의 regex를 컨트롤러 경로와 대조하기 때문
    deploy/server/caddy/*)
      verify=true; config=true ;;
    # reconcile bundle·hook — 이미지 무관
    deploy/server/docker-compose.yml|deploy/server/roles|deploy/server/readiness|deploy/server/required-env|deploy/server/bluegreen|deploy/hooks/*)
      config=true ;;
    # 양쪽 jar에 들어가는 빌드 입력 — 전부
    shared/src/main/*|shared/build.gradle.kts|trading-core/build.gradle.kts|build.gradle.kts|settings.gradle.kts|gradle.properties|gradle/*|gradlew|gradlew.bat|lombok.config|Dockerfile|.dockerignore)
      verify=true; api=true; scheduler=true; trading=true ;;
  esac
done

echo "verify=$verify"
echo "config=$config"
echo "api=$api"
echo "scheduler=$scheduler"
echo "trading=$trading"
