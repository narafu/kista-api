# kista-trading 서비스 분리 — 코드+DB 독립 설계

## 배경/목적

`kista-api`는 2026-09-04([[2026-09-04-scheduler-process-separation-design]])에 프로세스 레벨 2-role(`kista-api`/`kista-scheduler`)로 분리됐다. 같은 이미지를 `SCHEDULER_ENABLED` 플래그로 나눠 별도 컨테이너·별도 JVM으로 기동하며, 둘 다 `kista-postgres`에 직접 접속한다. 이 설계는 **API 장애가 매매 배치를 죽이지 않는다**는 목표를 이미 달성했다(커밋 `61781ce3`/`c0676522`/`77c0aa6c`) — 배치 실행 자체는 API 프로세스와 무관하게 계속 돈다.

이 문서가 다루는 건 그 이상이다: 사용자는 이 사실을 확인한 뒤에도 **코드베이스와 DB를 물리적으로 분리**하길 원했다. 동기는 배치 장애 격리가 아니라 **조직적 독립 운영**(배포 주기·빌드·팀 경계를 매매 서비스와 그 외 서비스로 완전히 나누는 것)이다.

### 09-04 설계가 기각한 것과 이 문서가 다른 점

09-04 문서(`2026-09-04-scheduler-process-separation-design.md:28-35`)는 Gradle 모듈 분리를 기각하며 핵심 리스크를 지목했다: **preview == execution 불변식**. 당시 구조에서 API가 매매 미리보기(`TradingPreviewService` 등)를 계산하고 스케쥴러가 실제 주문을 접수하는데, 둘을 별도 배포 아티팩트로 쪼개면 버전 스큐 시 미리보기와 실제 체결이 어긋날 수 있다는 우려였다.

이 문서의 경계 설계는 그 리스크를 구조적으로 없앤다: **preview·execution 둘 다 kista-trading 한 서비스가 소유**한다(`TradingCycleController`의 preview 엔드포인트까지 kista-trading으로 이전 — 아래 "2단계" 참고). kista-api는 프록시일 뿐 매매 계산을 하지 않으므로, kista-trading 단일 배포 안에서는 애초에 두 값이 갈라질 자리가 없다. 스큐가 생길 수 있는 유일한 지점은 kista-trading **자체의 롤링 배포 중** 구버전·신버전이 동시에 뜬 순간뿐이며, 이는 일반적인 단일 서비스 배포 문제로 축소된다(카나리 없이 단일 인스턴스 교체 배포로 충분히 낮은 리스크).

### DB SPOF에 대해

Postgres 단일 인스턴스는 DB만 분리해도 완전히 없어지지 않는다(kista-trading용 Postgres도 단일 인스턴스라면 그 자체가 SPOF). 이 설계는 SPOF 제거가 아니라 **책임 경계 분리**가 목적임을 명확히 한다 — DB 이중화·복제는 스코프 밖, 별도 인프라 작업.

## 채택 방향

### 서비스 이름과 경계: "스케쥴러"가 아니라 "매매 코어"

`@Scheduled` 9개 중 매매 데이터를 다루는 건 `TradingOpenScheduler`/`TradingCloseScheduler` 2개뿐이다. 나머지 7개(FearGreed, MarketCalendarRefresh, KbLand×2, MarketIndexPriceSync, RefreshTokenCleanup, FinanceReminder)는 `reference`/`finance`/`public` 데이터를 다뤄 kista-api에 남아야 한다. 따라서 분리 대상은 "스케쥴러"가 아니라 **매매 실행 도메인 전체**이며, 새 서비스명은 `kista-trading`이다.

### 소유권 경계

**kista-trading 소유** (탐색 결과 근거):

| 영역 | 모듈·클래스 | 테이블 |
|---|---|---|
| 매매 실행 | `trading` 전체 | `strategy`, `strategy_version`, `strategy_infinite_version`, `strategy_vr_version`, `strategy_cycle`, `strategy_cycle_vr`, `cycle_position`, `cycle_position_infinite`, `orders` |
| 계산 커널 | `matching` 전체 (`sharedkernel`+`privacy`에만 의존, `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`로 이미 강제됨) | — |
| 증권사 연동 | `broker` 전체 | `broker_tokens` |
| 계좌 | `account` 전체 | `accounts` |
| PRIVACY 기준표 | `privacy` 전체 (FK 없음 — 분리 마찰 최저) | `privacy_trade_bases`, `privacy_trade_base_orders` |
| 시장 캘린더 | `market`의 calendar 절반 | `us_market_holidays` |
| 매매 통계 계산 | `stats`의 매매 기반 부분(`StatsService` summary/equity-curve/cycles, `AccountStatisticsService`, `PortfolioService`, `BacktestService`) | — |
| 알림 발송 | `notify`(텔레그램 매매 알림) | — |
| 스케쥴러 | `TradingOpenScheduler`, `TradingCloseScheduler`, `MarketCalendarRefreshScheduler` + `platform.scheduling` | `scheduler_locks`(자체 사본) |

**kista-api 잔류**: `user`, `finance`, `admin`, `stats`의 벤치마크 부분(housing/ETF/index), `market`의 feargreed 부분, 나머지 스케쥴러 7개, `web` 앱셸. 테이블: `users`, `user_settings`, `user_notification_prefs`, `refresh_tokens`, `fcm_device_tokens`, `audit_logs`, `app_error_logs`, `admin_runtime_settings`, `fear_greed_snapshots`, `housing_*`, `market_index_prices`, `finance_*`.

### 끊어지는 결합

- **FK**: `accounts → users(id) ON DELETE CASCADE`가 유일하게 서비스 경계를 넘는 FK다(매매 계열 테이블은 전부 accounts/strategy 체인 경유라 kista-trading DB 안에서 그대로 유지된다). 앱 레벨 참조로 낮추고, 사용자 삭제 cascade는 이벤트로 처리한다.
- **사용자 데이터**: trading은 `users`를 갖지 않는다. `BatchContextFactory:37-42`가 배치마다 필요로 하는 `User`(알림·잔고검증용 최소 필드)는 kista-trading DB에 읽기 전용 복제본 `user_notify_profile`(userId, telegramBotToken(AES), chatId, notificationPrefs, balanceCheckEnabled, updatedAt)을 두고 kista-api의 변경 이벤트로 upsert 동기화한다. 매매 배치는 자기 DB만 읽으므로 API 장애와 완전히 무관해진다(현재도 무관하지만, DB 분리 후엔 공유 인프라 의존까지 0이 된다).
- **이벤트**: Modulith EPR(`event_publication` 테이블)은 DB 하나에 묶여 있어 두 DB를 못 넘는다. 서비스 내부 이벤트(trading→notify 매매 알림 11종 등)는 각자 DB의 EPR을 그대로 쓰고, 교차 이벤트만 **Redis Stream**을 태운다(Redis는 이미 인프라에 있음 — `TossRedisTokenStore`, `RedisBlacklistAdapter`, Spring Data Redis 의존성 기존 보유).

| 스트림 | 발행 | 구독 | 용도 |
|---|---|---|---|
| `user.deleted` | api | trading | 계좌·전략 cascade 삭제 |
| `user.notify-profile.changed` | api | trading | `user_notify_profile` upsert |

### 리포·빌드 구조

현 리포 내 Gradle 멀티프로젝트(`:trading-core`, `:api`). 별도 git 레포는 채택하지 않는다 — 공유 타입(`sharedkernel`) 버전 동기화 부담과 경계 리팩토링이 두 레포에 걸친 비원자적 커밋이 되는 문제 때문. ArchUnit·`ApplicationModules.verify()`를 계속 같은 빌드에서 강제할 수 있다는 이점도 크다.

## 단계 (일방향 문 마지막에)

각 단계 독립 배포 가능, 4단계 전까지 전부 되돌릴 수 있다.

### 1단계 — Gradle 멀티프로젝트 분리 (1 DB, 1 리포, 배포 형태 무변경)

`settings.gradle`에 `:trading-core` 추가. 위 경계표대로 소스 이동. `:api`가 `:trading-core`를 `implementation project(':trading-core')`로 의존(한 방향). `:api`의 `bootJar`가 여전히 두 모듈을 한 아티팩트로 묶어 배포 형태는 무변경 — **preview==execution 스큐 리스크가 이 단계에서 생기지 않는다**.

가치: ArchUnit·`ApplicationModules.verify()`가 위법 엣지를 전부 열거해준다. 이미 아는 큰 덩어리는 stats/admin → trading persistence adapter 재사용과 admin → broker 직접 호출.

**"순수 코드 이동"이 아니다 — 사전 정지 작업 2개 필요** (구현 계획 작성 중 실측으로 확인, Gradle이 컴파일 타임에 단방향 의존을 강제하므로 이 둘을 먼저 안 끊으면 `:trading-core`가 컴파일되지 않는다):

1. **trading→user 결합 절단**: `trading` 26개 파일이 `com.kista.user.*`를 import한다. 대부분(`BatchContext`, `TradingExecutionUseCase`, `TradingReporter`, `ManualTradingService` 등)은 텔레그램 봇 토큰·채팅ID·알림설정·`balanceCheckEnabled` 3종만 실제로 쓴다 → trading 소유 `TradingUserProfile`(domain.model) + `TradingUserProfilePort`(application.port.output) 신설, user가 구현(기존 `ApprovalPolicyPort`/`BrokerEnabledPort`와 동일한 포트 역전 패턴). `UserCascadeListener`/`StrategyUserCascadeListener`(`UserDeletedEvent` 구독)와 `ActiveStrategyCountAdapter`(user의 `ActiveStrategyCountPort` 구현)는 이미 올바른 방향이라 그대로 둔다. 이 작업은 DB 분리 전이라 새 테이블·동기화 없이 같은 DB 위에서 포트만 추가하면 된다 — **stage 3의 `user_notify_profile`을 전부 당겨오는 게 아니라, 그 타입 경계만 먼저 긋는 것**.
2. **market 패키지 분리**: `com.kista.market`는 Modulith `@ApplicationModule` 1개인데 calendar(→trading-core)/feargreed(→api)로 갈라져야 한다. trading이 import하는 건 `MarketCalendarPort` 단 하나뿐(`TradingService`, `VrCycleRolloverService`) — 오염 없이 깨끗하게 떨어진다. calendar 쪽을 새 최상위 패키지(예: `com.kista.marketcalendar`)로 물리 분리하고 `package-info.java`/NamedInterface를 다시 선언한다.

이 두 작업 완료 후에도 `./gradlew test` 그린이어야 한다(별도 커밋). 그다음에 `:trading-core` 골격을 만들고 소스를 옮긴다.

### 2단계 — 읽기 결합 끊기

stats·admin이 trading의 persistence adapter를 직접 쓰는 걸 내부 API 호출(`/api/internal/**`, 기존 `InternalTokenAuthFilter` 재사용)로 교체. **DB를 아직 공유하는 상태에서 하므로 옛 결과와 새 결과를 값 비교로 검증할 수 있다.**

`TradingCycleController`의 preview 엔드포인트(`GET /api/trading-cycles/{id}/preview`, `/strategy-seed-preview` 등)도 이 단계에서 kista-trading 내부 API로 승격한다 — 이게 preview==execution을 단일 서비스로 통합하는 지점.

### 3단계 — 쓰기 결합 끊기 + Redis Stream 배선

쓰기 경로(전략 CRUD, 수동 실행, 주문 취소, VR 재설정, 관리자 정정/재정렬, 계좌 CRUD)를 내부 API로. `user_notify_profile` 테이블 신설·백필 + Redis Stream 배선. 이 시점에 `:api → :trading-core` 컴파일 의존이 0이 되어야 한다(ArchUnit으로 고정).

### 4단계 — DB 분리 (되돌릴 수 없는 단계)

trading DB용 Flyway 베이스라인 신규 작성(`V1__init.sql`은 스쿼시된 이력이라 복사 불가). 데이터 이관(`pg_dump --table`). kista-api DB는 contract 마이그레이션으로 매매 테이블 드롭(코드 참조 제거 배포 먼저, 스키마 드롭 다음 — 기존 expand/contract 규율). 컷오버 창은 토요일 주간(매매는 22:30~04:30 MON–SAT).

## 테스트

- 단계마다: `./gradlew test` 전체 + `ApplicationModules.verify()` GREEN.
- 2단계 전용: DB 공유 상태에서 옛 경로(직접 DB)·새 경로(HTTP) 응답을 같은 입력으로 비교하는 일회성 검증. 통계 집계는 값 일치까지 확인.
- 3단계: MOCK 브로커 계좌로 로컬 2-프로세스 기동 → 전체 매매 플로우. kista-api를 내려도 매매 배치가 도는지 재확인(회귀 방지 — 09-04에서 이미 확보한 성질을 깨지 않았는지).
- 4단계: 스테이징 컷오버 리허설 1회 → 토요일 주간 실행.

## 게이트

1. 각 단계 `./gradlew test` 전체 그린.
2. 3단계 완료 시점: `:api`가 `:trading-core`에 컴파일 의존 0. kista-api 컨테이너 정지 상태에서 매매 개장 배치 1회 정상 완료(kista-trading 단독 실행 확인).
3. 4단계: 컷오버 후 첫 매매 사이클(개장·마감) 정상 관측(로그+heartbeat+텔레그램 리포트), 데이터 정합성(이관 전후 orders/cycle_position 건수 일치) 확인.

## 미해결/후속

- `AdminObservabilityController`의 anomalies 엔드포인트가 매매 테이블을 참조하는지 — 2단계 착수 전 `AdminQueryService.anomalies` 본문 확인.
- FCM 매매 알림: `fcm_device_tokens`가 `users` FK라 kista-api 잔류. 텔레그램만 kista-trading이 직접 발송, FCM은 이벤트로 api에 위임(기본안).
- `kista-infra` 레포의 docker-compose·Caddy 라우팅·배포 파이프라인 변경은 이 설계 범위 밖(4단계 착수 시 별도 작업).
- DB SPOF 자체(Postgres 이중화)는 스코프 밖 — 필요하면 별도 인프라 스펙.
