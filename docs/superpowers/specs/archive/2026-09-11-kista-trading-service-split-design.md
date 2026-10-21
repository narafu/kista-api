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
| 매매 통계 계산 | `stats`의 매매 기반 부분 — `com.kista.trading.stats`로 흡수(별도 모듈 아님): `StatsService` summary/equity-curve/cycles, `AccountStatisticsService`, `PortfolioService`, `BacktestService`/`BacktestEngine`/`FillSimulator`, `TossStatisticsService`, `BrokerStatisticsRouter`, `MonthlyReturnCalculator` | — |
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

1. **trading→user 결합 절단**: `trading` 26개 파일이 `com.kista.user.*`를 import한다. 대부분(`BatchContext`, `TradingExecutionUseCase`, `TradingReporter`, `ManualTradingService` 등)은 알림설정·`balanceCheckEnabled` 2종만 실제로 쓴다 → trading 소유 `TradingUserProfile`(domain.model) + `TradingUserProfilePort`(application.port.output) 신설. 실제로는 `user`가 아니라 `com.kista.web.trading.TradingUserProfileAdapter`가 구현했다 — trading이 이미 `ActiveStrategyCountAdapter`를 통해 user에 의존하고 있어 `user`쪽에 구현체를 두면 Modulith 슬라이스 순환이 생기기 때문(정의자도 자연스러운 데이터 소유자도 아닌 제3자 구현, → constraints.md "모듈 경계 own-type" 신규 전례 참고). `UserCascadeListener`/`StrategyUserCascadeListener`(`UserDeletedEvent` 구독, 이 이벤트는 이후 `com.kista.sharedkernel`로 승격됨)는 방향 그대로 유지됐지만, `ActiveStrategyCountAdapter`(user의 `ActiveStrategyCountPort` 구현)는 "이미 올바른 방향"이 아니었다 — 같은 순환 회피 사유로 Task 7에서 trading에서 `com.kista.web.trading`으로 재배치됐다. 이 작업은 DB 분리 전이라 새 테이블·동기화 없이 같은 DB 위에서 포트만 추가하면 된다 — **stage 3의 `user_notify_profile`을 전부 당겨오는 게 아니라, 그 타입 경계만 먼저 긋는 것**. **Stage 2 인수인계**: `com.kista.web.trading`(TradingUserProfileAdapter/ActiveStrategyCountAdapter)이 `kista-trading`을 독립 배포 서비스로 분리할 때 실제로 잘라야 하는 이음매다 — 둘 다 user와 trading-core 양쪽 데이터를 한 프로세스 안에서 오가므로, 서비스 분리 시 이 두 어댑터를 네트워크 호출(REST/이벤트)로 대체해야 한다.
2. **market 패키지 분리**: `com.kista.market`는 Modulith `@ApplicationModule` 1개인데 calendar(→trading-core)/feargreed(→api)로 갈라져야 한다. trading이 import하는 건 `MarketCalendarPort` 단 하나뿐(`TradingService`, `VrCycleRolloverService`) — 오염 없이 깨끗하게 떨어진다. calendar 쪽을 새 최상위 패키지(예: `com.kista.marketcalendar`)로 물리 분리하고 `package-info.java`/NamedInterface를 다시 선언한다.

이 두 작업 완료 후에도 `./gradlew test` 그린이어야 한다(별도 커밋). 그다음에 `:trading-core` 골격을 만들고 소스를 옮긴다.

### 2단계 — 읽기 결합 끊기 + admin 쓰기 결합 끊기

**판정 기준**: 결합 지점마다 "소비자가 kista-api에 남는가, kista-trading으로 옮겨가는가"를 먼저 묻는다. 옮겨가면 4단계 이후 같은 프로세스 내 호출이 되므로 지금 HTTP로 바꾸는 건 되돌릴 작업을 미리 하는 것 — API 전환 대상이 아니라 **소스 이동** 대상이다. api에 남는 소비자가 trading 데이터·타입을 필요로 할 때만 진짜 경계(내부 API 필요)다. 1단계 실측 결과 이 판정으로 스코프가 애초 문구보다 크게 갈렸다 — 아래가 확정 스코프.

**A. 선행 분리(각각 별도 커밋, `./gradlew test` 그린 확인 후 다음 진행 — 1단계 사전정지작업과 동일 규율)**

1. `com.kista.stats`는 Modulith 모듈 1개인데 절반은 trading 소유 read-model, 절반은 api 잔류 benchmark라 그대로 두면 한 모듈이 두 서브프로젝트에 걸친다. trading 몫(`StatsService`의 summary/equity-curve/cycles, `AccountStatisticsService`, `PortfolioService`, `BacktestService`/`BacktestEngine`/`FillSimulator`, `TossStatisticsService`, `BrokerStatisticsRouter`, `MonthlyReturnCalculator`)은 **별도 Modulith 모듈을 새로 만들지 않고 `com.kista.trading.stats`로 흡수**한다 — trading 자기 테이블(`strategy_cycle`/`cycle_position`) 위 read-model이라 자기 소유 모듈을 향한 참조뿐이며, 별도 CLOSED 모듈로 빼면 trading이 이 read-model만을 위해 도메인 타입+포트 4개를 새 NamedInterface로 공개해야 해 오히려 결합이 늘어난다. benchmark 몫(housing/ETF/index, `HousingBenchmarkComparisonBuilder` 포함)은 기존 `com.kista.stats`(api)에 그대로. `UserStatsUseCase`도 이 경계로 쪼개진다. `StatsResultCache`는 캐시 키 도메인(요약·equity-curve vs 벤치마크 비교)이 갈리므로 양쪽 각자 인스턴스를 둔다. `com.kista.trading.stats`가 `broker`(`BrokerAdapterRegistry`/`PortfolioPort`/`MarginPort`/`ExchangeRatePort`)와 `account`(`AccountPort`)를 참조하는 건 이미 trading-core 내부 모듈 간 참조라 새 엣지가 아니다 — 다만 `ApplicationModules.verify()`가 이 새 서브패키지발 `trading → broker`/`trading → account` 참조를 그린으로 통과시키는지는 실행 시점에 확인 대상으로 남긴다(놀랄 경우 설계 변경이 아니라 실측 확인 사항).
2. `StatsController`도 같은 경계로 쪼갠다 — benchmark 6개 라우트(housing/ETF/series/region)는 api 잔류, trading 3개 라우트(summary/equity-curve/cycles)는 **새 컨트롤러로 trading-core에 이동**한다(`TradingCycleController`와 동일 취급 — 단일 bootJar로 배포되는 동안 UI가 부르는 URL은 그대로, 어느 호스트로 라우팅할지는 4단계 몫).
3. `AdminQueryService`도 쪼갠다 — user/account/audit/errorlog 6개 메서드는 api 잔류 버전에 남기고, order/strategy 관련(`getAnomalies`의 order/strategy 조회분, `listTrades`, `listStrategyOrders`, `listStrategyTradeDates`, `getStrategySummariesByCycleIds`, `listStrategies`/`listStrategiesByAccountIds`, `listPrivacyBases`)은 제거하고 아래 C의 내부 API 호출로 교체한다.

**B. admin 쓰기 경로도 이 단계로 함께 당긴다** — `AdminReorderService`/`AdminTradeCorrectionService`(+공유 헬퍼 `AdminSelectionChain`/`AdminCycleCloser`)를 읽기와 분리해서 3단계로 미루지 않고 이번에 내부 API로 전환한다. 읽기와 헬퍼를 공유해 어중간하게 쪼개면 리팩토링을 두 번 하게 되는 게 이유. admin은 이 시점부터 재정렬·수동 정정·사이클 강제 종료·주문 취소를 전부 HTTP 너머로 위임한다(2단계가 원래 스펙의 "읽기만" 원칙보다 커진 지점 — 의도적 확장).

**C. 신규 내부 API 목록** (`/api/internal/**`, 기존 `InternalTokenAuthFilter` 재사용):

- 읽기: trades / strategy-orders / strategy-trade-dates / anomalies 소스(주문·전략 조회분) / strategy-summaries-by-cycle-ids / strategies(by-account) / privacy-bases
  - `privacy-bases`는 다른 항목과 판정 근거가 다르다: `privacy` 모듈 자체는 통째로 kista-trading으로 옮겨가지만, **소비자인 `AdminQueryService`는 api에 남는다** — "통째 이동=API 불필요" 규칙을 기계적으로 적용하면 이 엔드포인트를 빠뜨리게 되므로 명시해둔다.
- 쓰기: reorder(주문 재정렬 접수/취소), 수동 매매 정정, 사이클 강제 종료
- stats 전용 1개: `GET /api/internal/stats/investment-points` — trading 쪽이 `MonthlyReturnCalculator`로 계산까지 마친 `InvestmentPoint` 시리즈 + 최소 strategy 투영(id/type/ticker)을 반환하고, api는 이를 받아 순수 계산기인 `HousingBenchmarkComparisonBuilder`(api 잔류)로 벤치마크 시세와 조합한다. `MonthlyReturnCalculator`는 입력(`trading.domain.model.CyclePosition`/`StrategyCycle`)과 출력(계산된 시리즈) 둘 다 trading 데이터 기반이라 api 쪽에 잔여 필요가 없다 — 이 엔드포인트가 계산된 시리즈를 반환하는 순간 api 쪽 사본은 불필요해진다.
- 인가: userId를 그대로 전달하고 소유권 검증(`verifyOwnedBy`, `requireStrategyOwnedByAccount` 등)은 **trading 쪽에서 재검증**한다 — 분리 후 계좌·전략 둘 다 trading 소유라 api 쪽 사전검증 자체가 무의미해진다. admin 쓰기의 `audit_logs` 기록은 api 소유 테이블이라 api에 남기고, 내부 API 호출이 성공 응답을 반환한 뒤 api가 기록한다.

**D. own-type은 3단계로 미룬다** — 2단계에서는 `:api → :trading-core` 컴파일 의존(`build.gradle.kts`의 `implementation(project(":trading-core"))`)이 그대로 유지되므로 admin은 `trading.domain.model.Order`/`Strategy`/`StrategySummary`, `matching.domain.model.OrderTiming`을 계속 import할 수 있다(실측 확인됨). 내부 API 응답도 이 타입들로 그대로 역직렬화한다(Jackson 기본 — 필드 전부 UUID/BigDecimal/LocalDate/enum이라 문제 없음). own-type 신설은 이 컴파일 의존을 0으로 떨어뜨리는 3단계 작업이며, 그때 교체 대상은 약 20개 파일(`AdminReorderResult`의 `Order.OrderStatus`, `AdminReorderCommand`의 `OrderTiming`, DTO 8개 등)이다. `DstInfo`는 이 목록에서 아예 빠진다 — admin은 `reorderTimingAvailability()`의 결과값 `{atOpen, atClose, immediate}` 3개 boolean만 쓰므로 내부 엔드포인트가 이 값만 반환하면 되고, `DstInfo` 자체를 admin이 들고 있을 일이 없다. `AdminSelectionChain`의 `User` 의존도 own-type 대상이 아니라 **소멸 대상**이다 — `validate()`가 실제로 쓰는 건 `user.id()` 하나뿐(`account.userId().equals(...)` 신원 대조)이라, trading 쪽 검증기는 `User` 객체 없이 `UUID userId` 파라미터만 받아 `Account.userId()`와 비교하면 된다. admin은 user 존재 확인을 자기 쪽 `UserPort`로 그대로 유지.

**제외(2단계 아님, 통째 이동이라 HTTP 불필요)**: `TradingCycleController`/preview 엔드포인트(이미 1단계에서 trading-core로 이동 완료 — 라우팅 분리는 4단계), `AccountStatisticsService`/`PortfolioService`/`BacktestService`/`TossStatisticsService`/`BrokerStatisticsRouter`(전부 `com.kista.trading.stats`로 통째 이동).

**검증**: DB 공유 상태가 유지되므로 읽기 전환분은 기존 계획대로 옛 경로(직접 포트)·새 경로(HTTP) 값 비교. 쓰기(reorder/정정)는 실브로커 호출이라 이중 실행 비교가 불가능 — 대신 MOCK 브로커 계좌 통합테스트로 동등성 확인 후 스테이징 리허설 1회.

### 3단계 — 쓰기 결합 끊기

**게이트 재확인(실측)**: `:api → :trading-core` 컴파일 의존을 끊고 컴파일해보면(`implementation(project(":trading-core"))` 주석 처리 + `-Xmaxerrs 10000`) `com.kista.sharedkernel` 242건·`com.kista.platform` 72건이 즉시 걸린다 — 쓰기 6종 API 전환만으론 이 게이트에 못 닿는다. 3단계를 3a/3b로 분리한다.

#### 3a — `:shared` 추출 (선행, 1단계 사전정지작업과 동일 성격)

`sharedkernel`+`platform`을 신규 Gradle 서브프로젝트 `:shared`로 이동, `:api`·`:trading-core` 둘 다 `:shared`에 의존. 실측 확인(`grep -rn "^import com\.kista\." trading-core/src/main/java/com/kista/{sharedkernel,platform} | grep -v "com\.kista\.\(sharedkernel\|platform\)"` 결과 0건, `platform`→`sharedkernel` 참조도 0건): outbound-zero·상호 독립이라 순수 기계적 이동(디렉터리 이동 + `build.gradle.kts` 의존 선언만). `HexagonalArchitectureTest.platform_must_not_depend_on_other_modules`/`sharedkernel_must_not_depend_on_other_modules`, `GradleModuleBoundaryTest`를 3-서브프로젝트 그래프(`:shared`/`:trading-core`/`:api`) 기준으로 갱신. 단일 커밋, `./gradlew test` 그린 확인 후 3b 진행. 순차 단일 작업 — 서브에이전트 분리 불필요, 인라인 실행.

**스코프 제외(main 소스 게이트만 대상)**: `testFixturesImplementation(project(":trading-core"))`, trading-core 테스트의 `testImplementation(project(":"))` 역참조 — architecture.md에 이미 "보안 모듈 경계 정리 시 해소 대상"으로 명시된 별개 후속과제, 3단계 스코프 아님.

#### 3b — 쓰기 경로 전환 + own-type 정리

**스코프 재확정(실측)**: root(`admin`/`web`/`stats`/`user`)를 폭넓게 grep한 결과 `VrReconfigureUseCase`/`TradingExecutionUseCase`/`StrategyUseCase`/`AccountUseCase`(trading-core 소유) 소비자가 root에 0건이다 — 전략 CRUD·수동 실행·주문 취소·VR 재설정은 전부 `TradingCycleController`/`OrderCancelController`/`VrReconfigureService`(trading-core 내부) 자체가 같은 공유 포트에서 최종 사용자에게 직접 서빙하는 것이라 root import 자체가 존재하지 않는다 — **컴파일 의존 게이트와 무관**. 원래 문구의 "쓰기 6종" 목록은 "내부 API 전환"(3b 게이트)과 "프로세스 분리 대비"(4단계, kista-api가 실제 네트워크 프록시가 될 때 필요)를 혼동한 것이었다 — 이 네 플로우는 3b 스코프에서 빠지고 4단계로 미룬다(엔드유저 라우팅은 3b 동안 trading-core 네이티브로 그대로 유지). 아래는 실제 root→trading-core import 전수 조사로 확정한 목록이다.

**own-type/포트정리 작업** (동일 타입을 여러 에이전트가 동시 편집하는 충돌 방지 위해 endpoint 전환류보다 먼저 — `AdminOrderView`/`AdminStrategyView` 선례(`512084bb`) 재사용):

1. `TradingQueryPort.findStrategySummariesByCycleIds`의 `trading.domain.model.StrategySummary`(2필드: strategyId, strategyType) → admin own-type `AdminStrategySummary`
2. `TradingCommandPort.reorderTimingAvailability()`의 `DstInfo.ReorderTimingAvailability`(atOpen/atClose/immediate 3-boolean) → admin own-type `AdminReorderTimingAvailability`. 동시에 `AdminReorderService`/`AdminTradeCorrectionService`가 여전히 수행 중인 `AdminReorderCommand`↔`trading.ReorderCommand`, `AdminManualTradeCorrectionCommand`↔`trading.ManualTradeCorrectionCommand` 수동 필드 매핑도 제거 — `TradingCommandPort` 시그니처 자체를 admin own-type으로 바꾸면 필드명이 이미 동일해 매핑 코드가 불필요해진다(JSON 직렬화 shape 동일)
3. `TradingCommandHttpAdapter`/`GlobalExceptionHandler`가 함께 쓰는 `broker.domain.model.{BrokerCredentialException,BrokerRateLimitException}` → admin 소유 예외 타입으로 교체(둘 다 admin 코드에서만 생성·포착됨, 실측 확인)
4. `GlobalExceptionHandler`의 `trading.domain.model.{ManualTradingException,OrderCancelException}`, `privacy.domain.model.PrivacyTradeConflictException` 핸들러 — 실측 확인 결과 이 파일 밖 참조 0건(내부 API 전환 이후 도달 불가능한 죽은 매핑) → 삭제
5. `MetaController`/`StrategyTypeMeta`의 `matching.domain.strategy.{CycleOrderStrategies,CycleOrderStrategy}` 직접 참조(실측 확인, 2건) → trading-core 신규 내부 API(`GET /api/internal/meta/strategy-capabilities`, primitives만 담은 응답)로 대체
6. `com.kista.web.trading.ActiveStrategyCountAdapter`(user의 `ActiveStrategyCountPort` 구현체, `account.AccountPort`+`trading.StrategyPort` 직접 주입) → trading-core 신규 내부 API(`GET /api/internal/trading/active-strategy-count?userId=`)를 부르는 HTTP 어댑터로 교체
7. `AdminAccountController`/`AdminQueryUseCase.listAccounts`/`findAccount`가 `account.domain.model.Account`를 그대로 반환(포트 추상화 자체가 없던 read leak, 실측 확인) → `AdminAccountView` own-type + `AccountQueryPort`/`AccountQueryHttpAdapter` 신설(`TradingQueryPort` 선례와 동일 패턴)
8. `AdminPrivacyTradeService`가 `privacy.application.usecase.PrivacyUseCase`+`privacy.application.port.output.PrivacyTradePort`를 직접 주입(등록은 기존 `POST /api/internal/fida-orders` 재사용 가능, `updateBase`/`updateOrder`는 신규 내부 API 필요, 실측 확인) → `PrivacyQueryPort`를 `PrivacyTradePort`로 확장하고 admin own-type(`AdminPrivacyBaseView` 등)으로 응답
9. `AdminSchedulerController`(`com.kista.web`, 클래스 레벨 `@ConditionalOnProperty(scheduler.enabled)`) — trading 스케쥴러 트리거(`TradingOpenScheduler`/`TradingCloseScheduler`)와 stats KbLand 스케쥴러 트리거로 분리한다. trading 트리거 절반은 내부 API HTTP 호출로 바뀌므로 `scheduler.enabled` 게이팅이 불필요(원격 호출이라 로컬 빈 존재 여부 무관). KbLand 절반은 기존 게이팅 유지
10. `com.kista.stats`의 `trading.stats.domain.model.{InvestmentPoint,BenchmarkGranularity}` 참조(`InvestmentPointsPort`/`StatsService`/`HousingBenchmarkComparisonBuilder`) → own-type 전환. `InvestmentPointsHttpAdapter`는 Task5에서 이미 HTTP 경유로 전환되어 있어(2단계 완료분) 타입만 own-type으로 바꾸면 됨(신규 엔드포인트 불필요). architecture.md가 이 참조를 "영구적으로 남는 설계"로 서술해 3단계 게이트와 모순되므로 같은 커밋에서 정정(완료 — `c79550cc`)
11. **`TradingUserProfilePort`/`TradingUserProfileAdapter` 구조적 제거**: 이 포트는 trading-core가 정의하고 root(`com.kista.web.trading`)가 구현하는 역방향이라 컴파일 의존 0 이후엔 애초에 성립 불가능(인터페이스를 그 컴파일 의존 없이 구현할 수 없음) — import 정리가 아니라 구조 자체를 없애야 함. `user_notify_profile` 신설(아래)로 해소: trading-core가 자기 DB의 `user_notify_profile` 테이블을 직접 읽는 신규 `UserNotifyProfilePersistenceAdapter`(trading-core 내부)로 포트 구현을 교체하고, `TradingUserProfileAdapter`(web)와 root 쪽 어댑터 등록을 삭제. 소비처 3곳(`BatchContextFactory`/`MarketEventNotifier`/`StrategyCreationService`, 전부 trading-core 내부)은 포트 인터페이스가 그대로라 무변경
12. `UserCascadeDeleter`(user 모듈)의 `AccountPort.deleteByUserId(userId)` 직접 호출(동기, 트랜잭션 내부) — 컴파일 의존 0 이후 user가 trading-core 포트를 주입할 수 없다 → 제거하고 account 모듈에 `UserDeletedEvent` 구독 리스너(strategy/finance cascade와 동일 패턴, `@TransactionalEventListener(AFTER_COMMIT)`) 신설. **동작 변화**: 계좌 삭제가 동기(사용자 삭제 응답 이전 완료 보장)에서 비동기(커밋 후, EPR 재시도 보장)로 바뀜 — strategy/finance cascade가 이미 이 방식이라 일관성은 오히려 개선

**`user_notify_profile` 신설**: 테이블 + 백필(trading-core DB, 현재는 root와 같은 DB). 동기화는 두 이벤트 경로:
- `UserDeletedEvent`(이미 sharedkernel 소속) — 위 11번 리스너가 이 테이블도 함께 정리
- 프로필 변경(`telegramBotToken`/`chatId`/`notificationPrefs`/`balanceCheckEnabled`)은 오늘 발행되는 이벤트가 없다 — `UserSettingsService`/`UserService`의 해당 write 경로에 신규 `UserNotifyProfileChangedEvent`(user 모듈, 신규 코드) 발행 추가 필요. DB 아직 하나이므로 trading 쪽은 `@TransactionalEventListener`로 같은 Modulith EPR 사용

이 작업(`user_notify_profile` + 11번)은 `:api → :trading-core` 컴파일 의존을 0으로 만드는 마지막 게이트 조건이라 — 최종 `runtimeOnly` 전환 직전 선행 완료 필수.

**Redis Stream은 4단계로 미룬다**: 이 시점엔 프로세스가 하나라(단일 `app.jar`) Redis Stream이 실어 나를 프로세스 간 트래픽이 없다. 지금 배선해도 그게 존재 이유로 삼는 실패 모드(프로세스 분리 후 DB 이원화)를 테스트할 수 없다. 3b 종료 시점엔 `user_notify_profile` 동기화가 여전히 같은 DB 위 Modulith 이벤트로 동작 — 4단계 DB 분리 시점에 이 리스너를 Redis publish/subscribe로 교체한다.

**게이트 강제 메커니즘**: `build.gradle.kts`의 `implementation(project(":trading-core"))`를 `runtimeOnly(project(":trading-core"))`로 전환하는 것이 3b의 마지막 태스크다. `runtimeOnly`는 컴파일 클래스패스에서 제외되지만 `bootJar`가 참조하는 runtime 클래스패스엔 남아있어 — (1) root에 trading-core import가 하나라도 남아있으면 `./gradlew :compileJava`가 즉시 실패해 게이트를 기계적으로 강제하고, (2) 단일 `app.jar`엔 여전히 trading-core 클래스가 번들되어 컴포넌트 스캔·내부 API 루프백 호출(`INTERNAL_API_BASE_URL=http://localhost:8080` 기본값 그대로)이 무변경으로 동작한다. 검증은 컴파일 성공뿐 아니라 `unzip -l build/libs/app.jar | grep com/kista/trading`로 클래스 번들 여부까지 확인 — 컴파일만 통과하고 jar에서 빠지는 실패 모드(배포 시점에야 드러남)를 배제한다.

**Global Constraint — 프로세스 분리 아님**: 3b 종료 시점에도 `kista-api`/`kista-scheduler` 2-role은 여전히 같은 `app.jar` 하나에서 나온다. 내부 API 호출은 전부 프로세스 내부 루프백(같은 JVM, 같은 포트)이며 별도 포트·별도 배포 아티팩트가 생기지 않는다 — 그건 4단계(DB 분리) 몫이다.

3b 완료 시점 게이트: `:api → :trading-core` 컴파일 의존 0(main 소스 기준, ArchUnit으로 고정).

### 4단계 — DB 분리 (되돌릴 수 없는 단계)

trading DB용 Flyway 베이스라인 신규 작성(`V1__init.sql`은 스쿼시된 이력이라 복사 불가). 데이터 이관(`pg_dump --table`). kista-api DB는 contract 마이그레이션으로 매매 테이블 드롭(코드 참조 제거 배포 먼저, 스키마 드롭 다음 — 기존 expand/contract 규율). 컷오버 창은 토요일 주간(매매는 22:30~04:30 MON–SAT).

## 테스트

- 단계마다: `./gradlew test` 전체 + `ApplicationModules.verify()` GREEN.
- 2단계 전용: DB 공유 상태에서 옛 경로(직접 DB)·새 경로(HTTP) 응답을 같은 입력으로 비교하는 일회성 검증. 통계 집계는 값 일치까지 확인.
- 3단계: MOCK 브로커 계좌로 로컬 2-프로세스 기동 → 전체 매매 플로우. kista-api를 내려도 매매 배치가 도는지 재확인(회귀 방지 — 09-04에서 이미 확보한 성질을 깨지 않았는지).
- 4단계: 스테이징 컷오버 리허설 1회 → 토요일 주간 실행.

## 게이트

1. 각 단계 `./gradlew test` 전체 그린.
2. 3a 완료 시점: `:shared` 신설 후 `./gradlew test` 그린, ArchUnit 3-서브프로젝트 규칙 갱신 완료. 3b 완료 시점: `:api`가 `:trading-core`에 컴파일 의존 0(main 소스 기준). kista-api 컨테이너 정지 상태에서 매매 개장 배치 1회 정상 완료(kista-trading 단독 실행 확인).
3. 4단계: 컷오버 후 첫 매매 사이클(개장·마감) 정상 관측(로그+heartbeat+텔레그램 리포트), 데이터 정합성(이관 전후 orders/cycle_position 건수 일치) 확인.

## 미해결/후속

- ~~`AdminObservabilityController`의 anomalies 엔드포인트가 매매 테이블을 참조하는지~~ 확인 완료: `AdminQueryService.getAnomalies`가 `orderPort`/`strategyPort`를 직접 사용 — 2단계 스코프에 포함됨(위 "2단계" C 참고).
- FCM 매매 알림: `fcm_device_tokens`가 `users` FK라 kista-api 잔류. 텔레그램만 kista-trading이 직접 발송, FCM은 이벤트로 api에 위임(기본안).
- `notify` 모듈도 straddle 대상이다 — trading 매매 알림 11종(`TradingAlertNotifier` 등)은 kista-trading行, finance reminder·user lifecycle 알림(가입승인/거절 등)은 kista-api 잔류. 위 FCM 항목은 이 straddle의 일부만 다룬다 — 4단계 착수 시 `notify` 모듈 분리 방식(별도 모듈 vs 흡수)도 함께 결정 필요.
- `kista-infra` 레포의 docker-compose·Caddy 라우팅·배포 파이프라인 변경은 이 설계 범위 밖(4단계 착수 시 별도 작업).
- DB SPOF 자체(Postgres 이중화)는 스코프 밖 — 필요하면 별도 인프라 스펙.
