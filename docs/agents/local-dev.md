# 로컬 개발 환경 상세

자주 쓰는 한 줄 명령은 `commands.md`, 여기는 dev 토큰·dev 시드·2-프로세스 부팅 절차.

### 로컬 dev 토큰 발급 (DevAuthController, local 프로파일 전용)
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
- dev 유저(`...0001`)에만 적용: `[시드] 모의계좌`(MOCK) + ACTIVE INFINITE SOXL·VR TQQQ(API 등록), 가계부, KIS `[시드]` 계좌(MOCK 시드 계좌의 가짜 암호문 복사 — KIS 등록 API는 실연결 검증이라 SQL)·INFINITE SOXL 전략(PAUSED) 일별 포지션(equity-curve — MOCK은 집계 제외)·종결 주문(관리자 주문 관리), MOCK 계좌 INFINITE MAGX·USD(PAUSED, SQL) 사이클 25건(진행 중 2·종료 23 — 사이클 성과 목록 필터·페이징), BINANCE `[시드] 바이낸스`(ETH, USDT 시드 1000)·UPBIT `[시드] 업비트`(BTC, KRW 시드 1,500,000) 계좌의 DCA 전략(PAUSED, SQL) — 120일 전 시작 사이클·매일 04:30 스냅샷(회차마다 deposit 감소·holdings 증가, 60일 전 INJECT 포함)·최근 체결 BUY 3건(통화별 합산 summary·곡선·사이클 성과 검증). 시드 코인 계좌의 crypto-portfolio(실 거래소 잔고)는 가짜 자격증명이라 실패하는 것이 정상
- `dev-token`·`dev-admin-token`·`dev-e2e-token`은 호출마다 trading-core 사용자 복제본(`trading.user_notify_profile`)을 재동기화한다 — 로컬에서 Redis 초기화·trading 스키마 재생성으로 복제본이 유실되면 전략 등록이 404가 나기 때문이며, 시드는 복제본 반영을 최대 10초 기다린다
- Toss가 로컬 IP를 허용하지 않으면(로그 `IP address not allowed`) MOCK 전략 API 등록이 422로 실패한다 — SQL 시드(MAGX·USD 사이클 포함)는 그대로 적용된다
- MOCK 시세는 Toss 공용 피드라 trading-core `application-local.yml`에 `toss.admin-client-id`/`admin-client-secret` 필요 — 없으면 MOCK 전략 등록·preview가 422/503
- trading-core local은 스케쥴러가 켜져 있어 ACTIVE MOCK 전략이 실제로 돈다(DB 주문만 생성, 실패 시 관리자 텔레그램 알림)
- 로컬 `application-local.yml`(root·trading-core)의 `telegram.bot-token`은 운영과 다른 테스트 봇 토큰을 쓸 것 — 운영 봇이면 로컬 알림이 운영 관리자 chat에 섞인다
- 벤치마크(`kista_ref`)는 가짜 행 없이 수집기 트리거(kbland 2종 + ETF 지수 종가 `/api/admin/scheduler/market-index-prices`) — root를 `SCHEDULER_ENABLED=true`로 잠시 띄워야 엔드포인트가 열린다
  - 트리거 후 반드시 `SCHEDULER_ENABLED` 없이 재기동하고 `/api/admin/scheduler/*` 404로 꺼졌는지 확인할 것 — 켜둔 채 두면 로컬 cron이 운영과 같이 돈다(락은 DB별이라 못 막고, 로컬이 운영 봇을 쓰면 시작 알림이 운영 관리자 chat에 중복 발송).


### 로컬 2-프로세스 부팅 (root + trading-core)
2-role 배포와 별개로, root(`app.jar`)와 `trading-core`(`com.kista.tradingcore.TradingApplication`)를 로컬에서 **각자 다른 포트로 동시에** 띄워 내부 API 크로스콜(인증·Redis Stream 등)을 검증할 때 사용. 비밀값(JWT·AES·카카오·텔레그램·`internal.api.token`)은 양쪽 `application-local.yml`(.gitignored)이 공급하므로 환경변수는 프로세스 배선용만 준다:
```bash
# 두 jar 빌드
./gradlew bootJar                       # root -> build/libs/app.jar
./gradlew :trading-core:bootJar         # trading-core -> trading-core/build/libs/trading-core.jar

docker compose up -d postgres redis

# 빌드 타깃이 Java 25라 기본 java가 21 이하면 UnsupportedClassVersionError — Java 25 JDK의 java로 실행
J=/path/to/jdk-25/bin/java

# trading-core (8081) — root를 호출하지 않으므로 INTERNAL_API_BASE_URL 불필요
SERVER_PORT=8081 SPRING_PROFILES_ACTIVE=local nohup $J -jar trading-core/build/libs/trading-core.jar > logs/local-trading-stdout.log 2>&1 &

# root (8080) — INTERNAL_API_BASE_URL은 trading-core(8081)를 가리킴(기본값과 같아 생략 가능)
INTERNAL_API_BASE_URL=http://localhost:8081 SPRING_PROFILES_ACTIVE=local nohup $J -jar build/libs/app.jar > logs/local-api-stdout.log 2>&1 &
```
- `SPRING_PROFILES_ACTIVE=local` 누락 시 `DevAuthController`(`/api/auth/dev-token`)가 `@Profile("local")`로 비활성화돼 404 — 양쪽 프로세스 모두 필요
- `internal.api.token`은 양쪽 동일 값 필수(`X-Internal-Token` 상호 검증) — 로컬은 두 `application-local.yml`의 값을 맞춰 두고 `INTERNAL_API_TOKEN` 환경변수는 주지 않는다. 환경변수는 yml보다 우선하므로 한쪽에만 주면 그쪽만 덮어써 내부 API가 403이 되고, 값이 띄운 셸에만 남아 재기동 시 복원할 수 없다. 환경변수를 쓸 거면 반드시 양쪽에 같은 값을 준다(운영은 compose가 양쪽에 주입)
- `application-local.yml`에 비밀값이 없으면 위 명령에 `JWT_SIGNING_KEY`·`AES_ENCRYPTION_KEY`·`KAKAO_CLIENT_ID`(root)·`TELEGRAM_BOT_TOKEN`·`TELEGRAM_CHAT_ID`를 양쪽에 환경변수로 더한다
- 종료 시 `pkill -f "app.jar"`/`pkill -f "trading-core.*jar"`가 프로세스를 실제로 못 죽이는 경우가 있음 — `jps -l`로 PID 확인 후 `kill <pid>`
