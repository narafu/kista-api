# kista-trading 4단계 — 패키징+DB 분리 설계

`2026-09-11-kista-trading-service-split-design.md`(이하 "원 설계")의 4단계("DB 분리")를 구체화한다. Stage1~3(Gradle 컴파일 경계 분리, `:shared`/`:trading-core`/`:api` 3-서브프로젝트, `:api→:trading-core` 컴파일 의존 0)은 `worktree-kista-trading-gradle-split`(HEAD `33e8ead0`) 위에서 이미 완료됐고, 본 설계는 그 브랜치 위에 이어서 진행한다(main에 별도 merge하지 않음).

## 원 설계 대비 달라진 점 — 패키징도 쪼개야 한다

원 설계는 4단계를 "DB 분리"로만 정의했다. 하지만 Stage1~3 구현 과정에서 `TradingCycleController`/`AccountController`/`TradingStatsController`/`BacktestController` 등 **kista-ui가 JWT로 직접 호출하는 사용자 대상 컨트롤러**가 이미 `:trading-core`로 넘어가 있는 것이 드러났다(Stage2에서 preview==execution을 단일 소유로 만들기 위해 의도적으로 이관됨). 반면 `SecurityConfig`/`JwtAuthFilter`/`JwtDecoderConfig`/`InternalTokenAuthFilter`는 전부 root(`com.kista.user.adapter.in.web.security`)에만 있다.

`:trading-core`가 자체 `bootJar`로 쪼개지는 순간 이 인증 스택 전체가 빠진 채로 뜬다 — 내부 API뿐 아니라 사용자 로그인 인증 자체가 trading-core 프로세스에서 사라진다. 이는 "내부 API 인증 필터 하나 이관"이 아니라 **패키징 분리 자체가 하나의 하위 단계**임을 뜻한다. 따라서 4단계를 **4a(패키징+notify 분리, 되돌릴 수 있음)** / **4b(DB 분리, 컷오버 이후 되돌릴 수 없음)** 두 단계로 나눈다.

### 실측 근거
- 보안 스택 4파일 중 `SecurityConfig`/`JwtDecoderConfig`/`InternalTokenAuthFilter`는 `com.kista.user` import가 0개 — `:shared`로 그대로 이관 가능. `JwtAuthFilter`만 `BlacklistUseCase`(Redis 블랙리스트 체크) 1개 의존.
- kista-ui는 Caddy가 아니라 자체 Next.js BFF(`createProxyRoute`, `shared/lib/proxy/createProxyRoute.ts`)로 `API_BASE_URL` env var 하나만 보고 모든 `/api/**`를 프록시한다 — 라우팅 분기는 kista-infra의 Caddy 설정이 아니라 kista-ui 코드(`createProxyRoute`에 target 옵션 추가) + docker-compose 호스트네임 문제다.
- `user_notify_profile`(V22)엔 `telegramBotToken`/`chatId`가 없다 — trading 쪽 텔레그램 발송을 위해선 이 두 컬럼 신규 복제가 필요하다(원 설계가 암묵적으로 "이미 있다"고 가정한 부분).

### notify를 4b가 아니라 4a로 앞당긴 이유 — 프로세스 분리와 동시에 발생하는 알림 유실 gap
패키징만 먼저 쪼개고(4a) notify 이관을 4b까지 미루면, `TradingCycleController` 등이 trading-core 프로세스에서 발행하는 매매 이벤트(CycleCompletedEvent 등, 이미 sharedkernel 승격됨)를 root의 notify 리스너(아직 root에 있음)가 전혀 못 본다 — 같은 DB를 봐도 프로세스가 다르면 Spring의 로컬 `ApplicationEventPublisher`는 넘어가지 않는다. 매매 텔레그램 알림 6종 + 실시간 SSE(`TradingReportNotifier`→`RealtimeNotificationPort.notifyTrade`→`TradeSseEmitterRegistry`, 실측으로 확인된 경로)가 4a 배포~4b 완료 사이에 조용히 죽는다. 이 서비스가 향후 사용자를 늘릴 계획이 있어(본인 전용이 아님) 이 gap을 실사용자 영향으로 취급 — notify 이관을 4a에 포함시켜 gap 자체를 없앤다.

### notify 이관이 파일 이동이 아니라 포트 재설계인 이유 — 실측
- `UserNotificationPort`(`com.kista.notify.application.port.output`)는 파라미터로 **`com.kista.user.domain.model.User`(root 전용 도메인 객체)를 통째로 받는다**(`notifyCycleCompleted(User, ...)` 등). trading-core는 `User`를 소유하지 않으므로 이 포트를 그대로 가져갈 수 없다 — trading 전용 경량 프로필 타입(`user_notify_profile` 컬럼과 1:1)을 받는 새 포트로 재정의해야 한다.
- `CompositeUserNotificationAdapter`는 `user.notificationChannel()`(Telegram/FCM 라우팅)에 따라 `TelegramUserNotificationAdapter`(이관 대상)와 `FcmAdapter`(root 잔류, `fcm_device_tokens`가 `users` FK)를 함께 호출한다 — 이관 시 Telegram 절반은 trading-core가 직접 보내고, FCM 절반은 이벤트로 root에 위임해야 한다(원 설계 "FCM은 이벤트로 위임" 기본안과 일치, 여기서 구체화).
- `TelegramHttpClient`/`TelegramConfig`(package-private 유틸)는 admin bot(`TelegramAdapter`, root 잔류)도 함께 쓰므로 통째로 옮길 수 없다 — trading-core엔 같은 30줄짜리 유틸을 복제한다(기존 constraints.md "TelegramApiClient package-private 제약" 패턴과 동일 — 노출 대신 신규 어댑터).

## 4a단계 — 패키징 분리 (1 DB, 전부 되돌릴 수 있음)

### 보안 스택 `:shared` 이관
- `SecurityConfig`/`JwtDecoderConfig`/`InternalTokenAuthFilter`를 `com.kista.platform.security`(`:shared`)로 이동. `platform_must_not_depend_on_other_modules` ArchUnit 규칙을 그대로 통과해야 한다(현재도 `com.kista.user` 의존 0이므로 위반 없음).
- `JwtAuthFilter`도 `:shared`로 이관하되, `BlacklistUseCase` 직접 의존을 제거하고 `:shared`에 신규 `TokenBlacklistPort`(sharedkernel.port 계열, 기존 `BrokerEnabledPort` 승격 패턴과 동일)를 정의한다. root는 기존 `RedisBlacklistAdapter`가 이 포트를 구현하고, `:trading-core`도 같은 Redis 인스턴스를 보는 자체 `RedisBlacklistAdapter`(신규, 동일 키 네임스페이스)를 구현한다. Redis는 이미 공용 인프라(TossRedisTokenStore 등)라 신규 의존 추가 없음.
- `JWT_SIGNING_KEY` 환경변수는 이제 두 배포 아티팩트가 공유하는 시크릿이 된다 — kista-infra 시크릿 문서에 "양쪽 다 필요"로 명시.

### `:trading-core` 자체 bootJar
- `trading-core/src/main/java/com/kista/trading/TradingApplication.java` 신설 — `@SpringBootApplication` + `scanBasePackages`(trading/matching/broker/account/privacy/marketcalendar/sharedkernel/platform) + `@EntityScan`을 같은 패키지 집합으로 명시.
- `trading-core/build.gradle.kts`에 `bootJar { enabled = true }`로 전환(현재 `false`), 필요한 Spring Boot 플러그인 설정 보강.
- Flyway `spring.flyway.locations`를 root와 trading-core에서 서로 다른 디렉토리로 분리 — root는 기존 `db/migration` 유지, trading-core는 신규 `db/migration-trading`(4b단계에서 실제 파일 채움, 4a단계에선 빈 디렉토리+설정만).
- 게이트: **로컬 스크래치 DB(매매 테이블만 있는 임시 DB)에 대고 `TradingApplication`을 기동해 `ddl-auto: validate` 통과 + `/api/internal/**` 무토큰 401 + JWT 유효 토큰으로 `TradingCycleController` 200** 확인. 이 한 번의 기동이 스캔 범위·Flyway 분리·인증 스택 이관을 동시에 검증한다.

### notify 분리 + 포트 재설계
- 신규 `com.kista.trading.notify`(`:trading-core`, NamedInterface 없는 얇은 게이트웨이 — 기존 `com.kista.notify`와 동일 성격)에 매매 알림 6종(`TradingAlertNotifier`/`CycleEndedNotifier`/`CycleLifecycleNotifier`/`OrderCancelFailureNotifier`/`TradingReportNotifier`/`PrivacyAlertNotifier`) 이관.
- 신규 `TradingUserNotificationPort`(`com.kista.trading.notify.application.port.output`) — 기존 `UserNotificationPort`의 매매 관련 메서드만, `User` 대신 trading-core 소유 프로필 타입(`user_notify_profile` 그대로 매핑하는 record, 아래 확장 컬럼 포함)을 파라미터로 받는다. 6종 알림은 이 신규 포트만 의존 — root의 `User`/`UserNotificationPort`는 더 이상 참조하지 않는다.
- `TelegramUserNotificationAdapter`+`CompositeUserNotificationAdapter`(Telegram 절반)를 trading-core에 재구현 — Telegram은 직접 발송, FCM은 발송하지 않고 신규 `UserPushNotificationRequestedEvent`(userId, notificationType, payload — sharedkernel 또는 Redis pub/sub 페이로드)를 발행해 root에 위임.
- root `com.kista.notify`엔 `UserDeletedNotifier`/`UserFcmCleanupListener`/`StatsAlertNotifier`(KbLand)/`MarketAlertNotifier`(feargreed) + `FcmAdapter`/`FcmConfig`만 잔존. `CompositeUserNotificationAdapter`/`TelegramUserNotificationAdapter`는 root에서 삭제(더 이상 쓰는 곳 없음 — `UserNotificationPort`의 소비자가 이관된 6종뿐이었는지 구현 착수 시 재확인). `TelegramAdapter`/`TelegramConfig`/`TelegramHttpClient`/`TelegramProperties`(admin bot)는 root 잔존, trading-core엔 `TelegramHttpClient`/`TelegramConfig`(RestClient bean)만 최소 복제.
- root의 신규 리스너가 `UserPushNotificationRequestedEvent`(Redis pub/sub 구독)를 받아 기존 `FcmAdapter`로 발송.
- `SchedulerNotifier`는 프로세스마다 자기 쪽 스케쥴러의 `SchedulerLifecycleEvent`만 관측하므로 양쪽에 각자 얇은 리스너를 둔다(코드 복제가 아니라 이벤트 자체가 프로세스 로컬이라 자연히 그렇게 됨).

### `user_notify_profile` 확장 + trading-core 직접 조회 (Redis 불필요, 4a 한정)
- `telegram_bot_token VARCHAR(512)`(AES, 기존 `UserEntity.telegramBotToken`과 동일 규격), `chat_id` 컬럼 추가. 마이그레이션 시 기존 값 백필 필수(V22 때와 동일한 "복제본 비면 무증상 실패" 위험 — `findAllActive()` 계열 패턴 재확인).
- **4a에선 DB가 아직 공유이므로 별도 동기화 메커니즘이 필요 없다** — root의 기존 `UserNotifyProfileChangedEvent` 리스너가 지금처럼 `user_notify_profile` 테이블에 쓰고, trading-core는 자신의 JPA로 같은 물리 테이블을 그대로 읽는다. 두 프로세스가 갈라져도 DB가 같으니 즉시 일관됨 — `user.deleted`/`user.notify-profile.changed`를 Redis Stream으로 만드는 건 4b(DB가 실제로 갈라질 때)에서만 필요하다.

### `trade.event` — Redis Pub/Sub (4a에 즉시 필요, fire-and-forget)
- trading-core가 매매 이벤트 발생 시 `trade.event` 채널로 `TradeEventView`(기존 타입, sharedkernel 또는 trading own-type으로 재배치) publish. root의 신규 구독 컴포넌트(`com.kista.notify.adapter.out.sse`)가 수신해 기존 `TradeSseEmitterRegistry.send()`를 그대로 호출 — `TradeStreamController`/kista-ui 무변경.
- Stream이 아니라 **Pub/Sub**을 쓰는 이유: SSE 특성상 유실 시 UI 일시 끊김 정도라 내구성이 필요 없고(spec 원안의 fire-and-forget 판단 유지), consumer group·XACK 관리 오버헤드가 불필요하다.

### `scheduler_locks` 중복 확인
- `platform.scheduling.SchedulerLockService`는 `:shared` 소속이라 양쪽 프로세스가 각자의 `scheduler_locks` 테이블(4a는 같은 DB의 같은 테이블 공유, 4b부터 각자 DB)에 쓴다. 트레이딩 스케쥴러(open/close)와 나머지 7개(KbLand×2/feargreed/refresh-token/finance-reminder/market-calendar-refresh)는 lock key가 겹치지 않는 서로 다른 집합이므로 원천적으로 충돌 없음 — 구현 시 lock key 문자열 전수 grep으로 한 번만 재확인.

### 4a 게이트
- `./gradlew test` 전체 그린(두 서브프로젝트 각각의 `bootJar` 포함).
- 로컬에서 `kista-api.jar` + `kista-trading.jar` 동시 기동(1 DB 공유), kista-ui가 두 base URL로 정상 접속해 로그인→계좌조회→전략조회→매매사이클 조회 전 구간 동작.
- 로컬 Redis로 `trade.event` pub/sub 왕복 확인(trading-core 발행 → root `TradeStreamController` SSE로 수신).
- `ApplicationModules.verify()` GREEN 유지.

## 4b단계 — DB 분리 + 컷오버

DB가 실제로 갈라지면 `user_notify_profile` 동기화가 더 이상 "같은 테이블 직접 읽기"로 해결되지 않는다 — root가 쓴 변경을 trading DB의 복제본에 반영할 방법이 필요하다. 이 시점에 원 설계의 Redis Stream 2종을 배선한다.

4b는 리스크 프로파일이 다른 두 하위 단계로 나눈다 — **4b-1(되돌릴 수 있음, 실트래픽 관측 가능)**과 **4b-2(컷오버, 되돌릴 수 없음)**. 스트림 배선을 컷오버와 한 배포로 묶으면 신규 배선 결함과 돌이킬 수 없는 DB 전환 리스크가 뒤섞인다 — 분리하면 스트림은 공유 DB 위에서 실제 가입·승인·설정변경 트래픽으로 먼저 검증한 뒤, 컷오버는 이미 검증된 스트림 위에서만 진행한다.

### 사전 확인 완료 (구현 착수 전 실측, 2026-09-16)
- **root의 trading-core 직접 의존 0건**: `KistaApplication.scanBasePackages`가 이미 trading-core 소유 패키지(trading/matching/broker/account/privacy/marketcalendar) 전부를 스캔 범위에서 제외하고 있고, root 소스 전체에 `com.kista.{trading,account,broker,privacy,marketcalendar}.application.port`/`com.kista.matching.*` import가 0건이다 — architecture.md의 "AdminQueryService accountPort 잔존" 서술은 4a 작업 중 이미 해소된 stale 기록. 컷오버 시 root 쪽 스캔 범위·엔티티 참조를 건드릴 필요 없음.
- **event_publication 테이블은 신규 베이스라인에 반드시 포함**: trading-core `application.yml`엔 `spring.modulith.events.jdbc.schema-initialization` 설정이 없다(기본값 비활성화) — 지금은 root Flyway(V21)가 만든 물리 테이블 1개를 두 프로세스가 공유해서 동작할 뿐, trading DB가 분리되면 이 테이블이 trading DB에 없으면 EPR 자체가 죽는다. 신규 baseline에 V21과 동일 DDL로 포함(15개 매매 테이블 + 이 1개 = 16개).
- **search_path는 이미 해결됨**: trading-core datasource가 이미 Hikari `connection-init-sql: "SET search_path TO kista, finance, reference, public"`로 설정돼 있다 — 신규 DB에 스키마(`kista`/`reference`/`public`)만 만들면 됨, ALTER ROLE 불필요.
- **trading DB 이관 대상 16개 테이블 확정**(schema): `kista.accounts`/`kista.strategy`/`kista.strategy_version`/`kista.strategy_infinite_version`/`kista.strategy_vr_version`/`kista.strategy_cycle`/`kista.strategy_cycle_vr`/`kista.cycle_position`/`kista.cycle_position_infinite`/`kista.orders`/`kista.user_notify_profile`/`public.broker_tokens`/`public.event_publication`/`reference.privacy_trade_bases`/`reference.privacy_trade_base_orders`/`reference.us_market_holidays`. `public.scheduler_locks`는 `:shared` 소유 인프라 테이블이라 양쪽 DB 각자 보유(테이블 자체는 신규 생성, 데이터 이관 대상 아님).

### Redis Stream 2종 (내구성 필요)
| 스트림 | 발행 | 구독 | 내구성 |
|---|---|---|---|
| `stream:user.deleted` | api | trading | 필수(누락 시 계좌·전략 cascade 삭제 누락) — consumer group `trading-core` + XACK, 미처리 시 `XAUTOCLAIM`(유휴 60초 임계값)으로 매 스케쥴러 tick마다 복구 |
| `stream:user.notify-profile.changed` | api | trading | 필수(누락 시 복제본 drift, 무증상) — 위와 동일 메커니즘 |

- 발행 지점: 기존 로컬 이벤트 발행 지점(`UserCascadeDeleter`, `UserNotifyProfilePublisher`) 그대로 유지하고, 리스너만 로컬 `@TransactionalEventListener` → Redis Stream publish adapter로 교체.
- MAXLEN 트림(예: ~10000)으로 무한 누적 방지 — 트림 이후 유실 가능성은 컷오버 이후 reconciliation 배치가 보완(아래 참고).
- Redis 인스턴스: 기존 `kista-redis` 컨테이너 재사용(신규 스트림 키만 추가) — TossRedisTokenStore 등 이미 공용 인프라로 쓰는 중이라 신규 의존 없음.
- 배포 순서: **`kista-api`(발행자)를 먼저 배포**해야 한다 — constraints.md의 2-role 배포 순서 제약과 동일 원리(발행이 api 경로에만 있어 반대 순서면 trading이 구버전 상태에서 신규 스트림에 아무도 안 쓴 채로 얼마간 무증상 drift 발생).
- `trade.event` pub/sub은 4a에서 이미 배선 완료 — 4b에서 변경 없음, DB 분리와 무관.

### 4b-1 게이트 (되돌릴 수 있음)
- 스트림 배포 후 실트래픽(가입·승인·설정변경·탈퇴)으로 며칠간 왕복 관측 — trading-core `user_notify_profile` 갱신이 정상 반영되는지 로그로 확인.
- 문제 발견 시 리스너를 로컬 이벤트로 되돌리는 롤백 가능(DB는 아직 공유 상태이므로 물리 데이터 손실 없음).

### 4b-2 — DB 컷오버 (되돌릴 수 없음)
- 신규 trading DB: 같은 OCI 인스턴스에 신규 postgres 컨테이너(`kista-trading-postgres`) — docker-compose 서비스 추가, kista-infra 조율 필요.
- 스키마 부트스트랩: `CREATE SCHEMA kista/reference` (public은 기본 존재) — search_path는 위에서 확인한 대로 이미 설정됨.
- trading DB용 Flyway 베이스라인 `trading-core/src/main/resources/db/migration-trading/V1__init.sql` 신규 작성 — 위 16개 테이블 DDL을 root V1/V21/V22/V23에서 그대로 가져오되 `accounts.user_id → users(id)` FK는 **선언하지 않는다**(컬럼은 유지, 참조 무결성만 제외). 인덱스·제약조건은 원본과 동일하게(orders 관련 인덱스 포함).
- 데이터 이관(`pg_dump --table`, FK 의존 순서 고려 — 예: accounts → strategy → strategy_version/strategy_cycle → cycle_position/orders 순, 시퀀스 있는 테이블은 이관 후 `setval` 보정):
  - 15개 매매 테이블은 전체 복사.
  - `event_publication`은 **전체 복사 금지** — `listener_id LIKE 'com.kista.trading.%'` 등 trading-core 리스너 대상 row만 필터링해 이관(현재 물리 테이블 1개를 두 프로세스가 공유 중이라 root 리스너 행이 섞여 있음). 컷오버 직전 `completion_date IS NULL` 미완료 row를 양쪽 리스너 기준으로 먼저 확인.
- 컷오버 창: 토요일 주간(매매 22:30~04:30 MON–SAT 바깥). 실행은 사용자 수동 SSH 런북(스크립트+체크리스트만 준비, 이 세션이 직접 실행하지 않음).
- **롤백 윈도 = 1주일**: 컷오버 후 1주일간 kista-api DB에 매매 테이블을 남겨두고 관측만 한다. 단 이 창이 경과할수록 롤백 비용이 커진다 — 되돌리려면 그 사이 trading DB에 쌓인 매매 데이터를 역방향 pg_dump로 다시 api DB에 합쳐야 하고, 경과 시간에 비례해 손실·정합성 검증 부담이 커진다. 1주일 뒤 정상 확인되면 contract 마이그레이션으로 kista-api DB에서 16개 테이블 drop.

### 4b-2 게이트
- 스테이징 컷오버 리허설 1회(별도 태스크로 분리, 프로덕션 컷오버와 겹치지 않음).
- 컷오버 후 첫 매매 사이클(개장·마감) 정상 관측(로그+heartbeat+텔레그램 리포트).
- 데이터 정합성: 이관 전후 `orders`/`cycle_position` 건수 일치, `event_publication` 필터 이관 건수와 원본 조건 카운트 일치.
- 1주일 관측 기간 종료 후 drop 마이그레이션 배포.

### reconciliation 배치 (컷오버 이후 별도 배포로 축소)
`user_notify_profile` 주기적(예: 일 1회) drift 점검(`users`/`user_settings` 카운트·`updated_at` 대조, drift 시 관리자 알림)은 컷오버에 태우지 않는다 — trading-core→root 방향 신규 내부 HTTP 호출이 필요한데, 현재 내부 API 어댑터는 전부 root→trading-core 단방향이라(`AccountQueryHttpAdapter` 등) 역방향 배선(Caddy 라우팅, RestClient 빈, bare path 함정 재확인)이 별도 검증을 요구한다. 4b-2 컷오버 완료 후 별도 태스크로 진행.

## 미해결/후속
- kista-ui의 정확한 trading 소유 route.ts 목록·kista-trading 컨테이너 배포 배선은 4a에서 이미 확정·배포 완료(`f631ba52`, kista-ui `f639a8b`, kista-infra `3822dba`) — 4b는 이 위에서 진행.
- FCM 매매 알림은 원 설계 기본안대로 확정: `fcm_device_tokens`가 `users` FK라 kista-api 잔류, trading은 `UserPushNotificationRequestedEvent`만 발행.
- root `UserNotificationPort`의 남은 소비자는 `FinanceRegistrationReminderNotifier` 1건뿐(실측 확인) — finance 알림용으로 의도된 잔존, 인터페이스 삭제 대상 아님.

## 테스트
- 4a: `./gradlew test` 전체 + 로컬 2-jar 기동 스모크(보안 스택·kista-ui 라우팅·`trade.event` pub/sub 왕복 포함, 위 게이트 참고) — 완료.
- 4b-1: 스트림 배포 후 실트래픽 관측(별도 테스트 스위트 없음, 로그·DB 상태 확인).
- 4b-2: 스테이징 리허설 1회 → 토요일 주간 실행. 컷오버 전/후 `orders`/`cycle_position`/`event_publication`(필터 기준) 건수 비교 스크립트.
