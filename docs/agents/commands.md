## 자주 쓰는 명령어

### Gradle
```bash
./gradlew bootJar                                               # app.jar 빌드
./gradlew bootRun --args='--spring.profiles.active=local'      # 로컬 실행
./gradlew test                                                  # 전체 테스트
./gradlew compileJava                                           # 컴파일만
./gradlew test --tests 'com.kista.architecture.*'               # ArchUnit 규칙만
./gradlew test --tests 'com.kista.domain.*'                     # 도메인 단위 테스트 (레거시 잔류분)
./gradlew test --tests 'com.kista.trading.domain.*'              # trading 도메인 단위 테스트 (order/strategy 실행 이력)
./gradlew test --tests 'com.kista.broker.adapter.out.kis.*'     # KIS Adapter 테스트
./gradlew test --rerun-tasks                                    # 캐시 무시 강제 재실행
./gradlew :trading-core:test                                     # trading-core 서브프로젝트만 테스트
./gradlew clean compileJava                                     # QueryDSL 생성파일 캐시 오염 시 (QXxxEntity.java "error reading")
# 테스트 실패 진단: stdout보다 XML이 신뢰성 높음
grep -oP 'failures="\K[^"]+' build/test-results/test/TEST-*.xml | grep -v ':0'
```

### 로컬 admin 토큰 발급 (DevAuthController, local 프로파일 전용)
```bash
# 일반 사용자 토큰
TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $TOKEN" localhost:8080/api/admin/_ping  # 403 기대

# ADMIN 토큰 (고정 UUID 00000000-0000-0000-0000-000000000002)
ADMIN_TOKEN=$(curl -s -X POST localhost:8080/api/auth/dev-admin-token | jq -r .accessToken)
curl -i -H "Authorization: Bearer $ADMIN_TOKEN" localhost:8080/api/admin/_ping  # 200 기대
```

### 로컬 2-프로세스 부팅 (root + trading-core, 4a Task 10 스모크 테스트 실측)
2-role 배포와 별개로, root(`app.jar`)와 `trading-core`(`TradingApplication`)를 로컬에서 **각자 다른 포트로 동시에** 띄워 내부 API 크로스콜(인증·Redis Pub/Sub 등)을 검증할 때 사용. 최소 필요 환경변수는 `docs/agents/commands.md`가 자동 로드하는 CLAUDE.md 필수 목록보다 많다 — 실측 결과:
```bash
# 두 jar 빌드
./gradlew bootJar                       # root -> build/libs/app.jar
./gradlew :trading-core:bootJar         # trading-core -> trading-core/build/libs/trading-core-0.0.1-SNAPSHOT.jar

docker compose up -d postgres redis

# root (8080) — INTERNAL_API_BASE_URL은 trading-core(8081)를 가리킴
JWT_SIGNING_KEY='...' AES_ENCRYPTION_KEY='...' KAKAO_CLIENT_ID='...' \
TELEGRAM_BOT_TOKEN='...' TELEGRAM_CHAT_ID='...' \
INTERNAL_API_TOKEN='local-token' INTERNAL_API_BASE_URL=http://localhost:8081 \
SPRING_PROFILES_ACTIVE=local java -jar build/libs/app.jar &

# trading-core (8081) — INTERNAL_API_BASE_URL은 반대로 root(8080)를 가리킴
JWT_SIGNING_KEY='...' AES_ENCRYPTION_KEY='...' \
TELEGRAM_BOT_TOKEN='...' TELEGRAM_CHAT_ID='...' \
INTERNAL_API_TOKEN='local-token' INTERNAL_API_BASE_URL=http://localhost:8080 SERVER_PORT=8081 \
SPRING_PROFILES_ACTIVE=local java -jar trading-core/build/libs/trading-core-0.0.1-SNAPSHOT.jar &
```
- `SPRING_PROFILES_ACTIVE=local` 누락 시 `DevAuthController`(`/api/auth/dev-token`)가 `@Profile("local")`로 비활성화돼 404 — 양쪽 프로세스 모두 필요
- `INTERNAL_API_TOKEN`은 양쪽에 동일 값 필수(`X-Internal-Token` 상호 검증)
- 종료 시 `pkill -f "app.jar"`/`pkill -f "trading-core.*jar"`가 이 환경에서 프로세스를 실제로 못 죽이는 경우가 있음 — `jps -l`로 PID 확인 후 `taskkill //F //PID <pid>`로 대체

### Docker (로컬)
```bash
docker compose up -d                                            # PostgreSQL + Redis + Prometheus + Grafana (앱은 IntelliJ 또는 bootRun으로 별도 실행)
docker compose up -d postgres                                   # DB만 기동
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
