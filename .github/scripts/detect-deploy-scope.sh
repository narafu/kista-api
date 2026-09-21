#!/usr/bin/env bash
# 변경 파일 경로 목록(stdin) → 배포 범위 플래그(stdout, key=value 한 줄씩)
#   verify  : 코드·테스트·빌드 입력이 바뀜 → 전체 테스트 실행
#   api     : app.jar 산출물이 바뀜 → kista-api / kista-scheduler 배포 (같은 jar)
#   trading : trading-core.jar 산출물이 바뀜 → kista-trading 배포
# 테스트 전용 경로는 jar에 들어가지 않으므로 verify만 켠다. 판정 불가한 공용 입력은 전부 켠다.
set -euo pipefail

verify=false
api=false
trading=false

while IFS= read -r f; do
  case "$f" in
    # 테스트 소스 — 산출물 무관, 검증만
    src/test/*|src/testFixtures/*|*/src/test/*|*/src/testFixtures/*)
      verify=true ;;
    # trading-core.jar 전용
    trading-core/src/main/*)
      verify=true; trading=true ;;
    # 스키마 변경 — root가 적용하고 trading-core는 ddl-auto: validate로 검증하므로 둘 다
    src/main/resources/db/migration/*)
      verify=true; api=true; trading=true ;;
    # app.jar 전용
    src/main/*)
      verify=true; api=true ;;
    # 양쪽 jar에 들어가거나 3역할이 공유하는 입력 — 전부
    shared/src/main/*|shared/build.gradle.kts|trading-core/build.gradle.kts|build.gradle.kts|settings.gradle.kts|gradle.properties|gradle/*|lombok.config|Dockerfile|deploy/*|.github/scripts/*|.github/workflows/server-deploy.yml|.github/workflows/_deploy-role.yml)
      verify=true; api=true; trading=true ;;
  esac
done

echo "verify=$verify"
echo "api=$api"
echo "trading=$trading"
