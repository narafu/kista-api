## 자주 쓰는 명령어

### Gradle
```bash
./gradlew bootJar                                               # app.jar 빌드
./gradlew :bootRun --args='--spring.profiles.active=local'     # 로컬 실행 (root 지정 필수 — 접두사 없으면 :shared:bootRun이 mainClass 없음으로 실패)
./gradlew test                                                  # 전체 테스트
./gradlew compileJava                                           # 컴파일만
./gradlew :test --tests 'com.kista.architecture.*'              # 루트 ArchUnit·Modulith 규칙 (HexagonalArchitectureTest 등)
./gradlew :trading-core:test --tests 'com.kista.trading.domain.*'  # trading 도메인 단위 테스트 (trading-core 소속 — 루트 test 태스크엔 없음)
./gradlew :trading-core:test --tests 'com.kista.broker.adapter.out.kis.*'  # KIS Adapter 테스트 (trading-core 소속)
./gradlew test --rerun-tasks                                    # 캐시 무시 강제 재실행
./gradlew :trading-core:test                                     # trading-core 서브프로젝트만 테스트
./gradlew clean compileJava                                     # 빌드 캐시 오염 시 클린 컴파일
# --tests 필터는 반드시 서브프로젝트 지정(:test / :trading-core:test) — 접두사 없는 `test --tests X`는 모든 서브프로젝트에 적용돼 X가 없는 :shared가 "No tests found"로 BUILD FAILED
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

### 배포 스크립트 검증 (Docker)
```bash
# bats(.github/tests) — 판정 스크립트·원격 배포 스크립트(docker 스텁)
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -aw .):/code" -w /code --entrypoint sh bats/bats:latest \
  -c "apk add -q git; git config --global --add safe.directory '*'; bats .github/tests"
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -aw .):/mnt" -w /mnt koalaman/shellcheck:stable .github/scripts/*.sh deploy/hooks/*.sh .github/tests/stub/gh
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -aw .):/repo" -w /repo rhysd/actionlint:latest
bash .github/scripts/check-migrations.sh origin/main   # 마이그레이션 불변·expand/contract
```
- 로컬 테스트 JVM은 Gradle `testJvmSlot` 빌드 서비스로 한 번에 하나만 뜬다(`CI` 환경변수 없을 때) — `test integration`처럼 여러 테스트 태스크를 한 번에 돌려도 순차 실행

### 로컬 admin 토큰 발급 (DevAuthController, local 프로파일 전용)
```bash
# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403 기대

# ADMIN 토큰 (고정 UUID 00000000-0000-0000-0000-000000000002)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200 기대
```

### 로컬 dev 시드 (kista-ui 화면 검증용)
```bash
scripts/dev-seed/seed.sh   # root(8080)·trading-core(8081) local 기동 상태에서 실행, 재실행 가능
```
- dev 유저(`...0001`)에만 적용: `[시드] 모의계좌`(MOCK) + ACTIVE INFINITE SOXL·VR TQQQ(API 등록), 가계부, KIS `[시드]` 전략(PAUSED) 일별 포지션(equity-curve — MOCK은 집계 제외)·종결 주문(관리자 주문 관리)
- MOCK 시세는 Toss 공용 피드라 trading-core `application-local.yml`에 `toss.admin-client-id`/`admin-client-secret` 필요 — 없으면 MOCK 전략 등록·preview가 422/503
- trading-core local은 스케쥴러가 켜져 있어 ACTIVE MOCK 전략이 실제로 돈다(DB 주문만 생성, 실패 시 관리자 텔레그램 알림)
- 벤치마크(`kista_ref`)는 가짜 행 없이 kbland 수집기 트리거 — root를 `SCHEDULER_ENABLED=true`로 잠시 띄워야 엔드포인트가 열린다. ETF 시계열(`market_index_prices`)은 수동 트리거가 없어 09:00 KST cron 전용
  - 트리거 후 반드시 `SCHEDULER_ENABLED` 없이 재기동할 것 — 켜둔 채 두면 로컬 cron이 운영과 같이 돈다(락은 DB별이라 못 막고 관리자 봇은 공유라 시작 알림이 중복 발송). 2026-10-03 ETF 동기화 중복 알림 사례

### 로컬 2-프로세스 부팅 (root + trading-core)
2-role 배포와 별개로, root(`app.jar`)와 `trading-core`(`tradingweb.TradingApplication`)를 로컬에서 **각자 다른 포트로 동시에** 띄워 내부 API 크로스콜(인증·Redis Stream 등)을 검증할 때 사용. 최소 필요 환경변수는 CLAUDE.md의 필수 목록보다 많다 — 실측 결과:
```bash
# 두 jar 빌드
./gradlew bootJar                       # root -> build/libs/app.jar
./gradlew :trading-core:bootJar         # trading-core -> trading-core/build/libs/trading-core.jar

docker compose up -d postgres redis

# root (8080) — INTERNAL_API_BASE_URL은 trading-core(8081)를 가리킴
JWT_SIGNING_KEY='...' AES_ENCRYPTION_KEY='...' KAKAO_CLIENT_ID='...' \
TELEGRAM_BOT_TOKEN='...' TELEGRAM_CHAT_ID='...' \
INTERNAL_API_TOKEN='local-token' INTERNAL_API_BASE_URL=http://localhost:8081 \
SPRING_PROFILES_ACTIVE=local java -jar build/libs/app.jar &

# trading-core (8081) — root를 호출하지 않으므로 INTERNAL_API_BASE_URL 불필요(토큰은 수신 검증용으로만 필요)
JWT_SIGNING_KEY='...' AES_ENCRYPTION_KEY='...' \
TELEGRAM_BOT_TOKEN='...' TELEGRAM_CHAT_ID='...' \
INTERNAL_API_TOKEN='local-token' SERVER_PORT=8081 \
SPRING_PROFILES_ACTIVE=local java -jar trading-core/build/libs/trading-core.jar &
```
- `SPRING_PROFILES_ACTIVE=local` 누락 시 `DevAuthController`(`/api/auth/dev-token`)가 `@Profile("local")`로 비활성화돼 404 — 양쪽 프로세스 모두 필요
- `INTERNAL_API_TOKEN`은 양쪽에 동일 값 필수(`X-Internal-Token` 상호 검증)
- 종료 시 `pkill -f "app.jar"`/`pkill -f "trading-core.*jar"`가 이 환경에서 프로세스를 실제로 못 죽이는 경우가 있음 — `jps -l`로 PID 확인 후 `taskkill //F //PID <pid>`로 대체

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
```
# application-local.yml에 logging.file.name: {프로젝트루트}/logs/kista-api.log 설정됨

## 배포/인프라/외부 연동 런북

저빈도 운영 작업 — 필요시 `docs/agents/docker-infra.md` 참고:
- 서버(OCI) 배포 모니터링/환경변수 설정
- kista-ui 운영 로그 조회, kista-api↔kista-ui URL 변경 연동
- kis-trade-mcp 재시작, .mcp.json 경로 이식성
- Privacy 기준표 운영 → 로컬 마이그레이션
