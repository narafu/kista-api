## 자주 쓰는 명령어

### Gradle
```bash
./gradlew bootJar                                               # app.jar 빌드
./gradlew :bootRun --args='--spring.profiles.active=local'     # 로컬 실행 (root 지정 필수 — 접두사 없으면 :shared:bootRun이 mainClass 없음으로 실패)
./gradlew test                                                  # 전체 테스트
./gradlew :test --tests 'com.kista.api.architecture.*'              # 루트 ArchUnit·Modulith 규칙 (HexagonalArchitectureTest 등)
./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*'  # trading-core Modulith 검증(ModulithArchitectureTest 등)
./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.*'  # trading 도메인 단위 테스트 (trading-core 소속 — 루트 test 태스크엔 없음)
./gradlew :trading-core:test --tests 'com.kista.tradingcore.broker.adapter.out.kis.*'  # KIS Adapter 테스트 (trading-core 소속)
./gradlew :trading-core:test                                     # trading-core 서브프로젝트만 테스트
# --tests 필터는 반드시 서브프로젝트 지정(:test / :trading-core:test) — 접두사 없는 `test --tests X`는 모든 서브프로젝트에 적용돼 X가 없는 :shared가 "No tests found"로 BUILD FAILED
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -l 'failures="[1-9]' build/test-results/test/TEST-*.xml   # 실패가 있는 테스트 XML 파일 목록
```

### 배포 스크립트 검증 (Docker)
```bash
# bats(.github/tests) — 판정 스크립트·원격 배포 스크립트(docker 스텁)
docker run --rm -v "$(pwd):/code" -w /code --entrypoint sh bats/bats:latest \
  -c "apk add -q git; git config --global --add safe.directory '*'; bats .github/tests"
docker run --rm -v "$(pwd):/mnt" -w /mnt koalaman/shellcheck:stable .github/scripts/*.sh deploy/hooks/*.sh .github/tests/stub/gh
docker run --rm -v "$(pwd):/repo" -w /repo rhysd/actionlint:latest
bash .github/scripts/check-migrations.sh origin/main   # 마이그레이션 불변·expand/contract
```
- Windows Git Bash: docker 볼륨 마운트 시 `MSYS_NO_PATHCONV=1` + `$(cygpath -aw .)` 사용
- 로컬 테스트 JVM은 Gradle `testJvmSlot` 빌드 서비스로 한 번에 하나만 뜬다(`CI` 환경변수 없을 때) — `test integration`처럼 여러 테스트 태스크를 한 번에 돌려도 순차 실행

### 로컬 admin 토큰 발급 (DevAuthController, local 프로파일 전용)
```bash
# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403 기대

# ADMIN 토큰 (고정 UUID 00000000-0000-0000-0000-000000000002)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200 기대

# E2E 전용 빈 사용자 토큰 (고정 UUID ...0003, dev 시드 미적용 — kista-ui account-cache E2E가 사용)
E2E_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-e2e-token | jq -r .accessToken)
```

### 로컬 dev 시드 (kista-ui 화면 검증용)
```bash
scripts/dev-seed/seed.sh   # root(8080)·trading-core(8081) local 기동 상태에서 실행, 재실행 가능
```
- 안전: 로컬 `telegram.bot-token`은 운영과 다른 테스트 봇이어야 한다 — 상세(시드 내용·jq·Toss 제약) → `docs/agents/local-dev.md`
- **SCHEDULER_ENABLED 같은 일회성 override로 띄웠다가 원복했다면 재기동 후 실제 동작으로 확인한다.** 예를 들어 `/api/admin/scheduler/*`가 404인지 본다. 검증 전에는 "원복 완료"라고 보고하지 않는다. 환경변수 override(IntelliJ MCP `execute_run_configuration` envs 등)는 persist되지 않는다고 안내돼도 그대로 믿으면 안 된다. 로컬과 운영이 관리자 봇을 공유하고 스케줄러 락은 DB별이라, 켜진 채로 두면 운영과 같은 cron이 돌아 알림이 중복 발송된다.

### 로컬 2-프로세스 부팅 (root + trading-core)
root(`app.jar`, 8080)와 `trading-core.jar`(8081)를 동시에 띄워 내부 API 크로스콜을 검증한다. 빌드는 `./gradlew bootJar :trading-core:bootJar`, 두 프로세스 모두 `SPRING_PROFILES_ACTIVE=local` 필요, `internal.api.token`은 양쪽 yml 값을 같게 맞출 것. 상세 절차 → `docs/agents/local-dev.md`

### Docker (로컬)
```bash
docker compose up -d                                            # PostgreSQL + Redis + Prometheus + Grafana (앱은 IntelliJ 또는 bootRun으로 별도 실행)
docker compose up -d postgres                                   # DB만 기동
docker compose down -v                                          # 로컬 DB 초기화(볼륨 삭제) — 스키마 재편 전 옛 레이아웃 DB는 이걸로 재생성(서비스별 baseline이 fresh 적용)
docker compose build <service> && docker compose up -d --force-recreate <service>
```

### 로컬 서버 로그 (IntelliJ 실행 시)
```bash
tail -f logs/kista-api.log
tail -f logs/kista-trading.log   # trading-core (kista-trading)
```
# application-local.yml에 logging.file.name: {프로젝트루트}/logs/kista-api.log 설정됨
