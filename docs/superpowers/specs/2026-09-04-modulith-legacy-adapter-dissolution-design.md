# 레거시 `com.kista.adapter`/`com.kista.application` shim 해소 — `web` 앱셸 + `platform` 인프라 모듈 신설 설계

## 배경/목적

11모듈 Spring Modulith 이전([[2026-08-31-legacy-module-catalog-design]])이 끝난 뒤에도 레거시 최상위 shim 패키지 3개가 남아 있다.

| 패키지 | 파일 수 | `@ApplicationModule` | 내용 |
|---|---|---|---|
| `com.kista.common` | 5 | `Type.OPEN` | Spring/JPA 무의존 순수 유틸 (`CycleLookups`/`Sha256`/`TimeZones`/`UsTradeDates`) |
| `com.kista.application` | 3 | `Type.OPEN` | `MetricsConfig`, `RealtimeNotificationPort`, `package-info` |
| `com.kista.adapter` | ~45 | `Type.OPEN` | 크로스모듈 컨트롤러 8개 + dto ~30 + SSE 레지스트리 2 + crypto 2 + persistence base 3 + scheduler 골격 2 + `ErrorLogAspect` + openapi customizer |

`Type.OPEN`이라 `ModulithArchitectureTest`의 `ApplicationModules.verify()`가 이 패키지를 드나드는 의존을 **검사하지 않는다**. 즉 "깨끗한 `verify()`"가 실제보다 약한 증거다 — advisor 구조 검토가 지적한 항목([[project_modulith_post_migration_backlog]] #4).

목적: `com.kista.adapter`/`com.kista.application` shim을 소멸시키고, 그 내용물을 (a) 진짜 앱 레벨 관심사만 담는 얇은 `com.kista.web` inbound 모듈, (b) 정직하게 outbound-zero를 증명하는 `com.kista.platform` 인프라 모듈, (c) 단일 소유자가 명확한 코드는 해당 모듈로 — 3방향으로 정리한다. `com.kista.common`과 `com.kista.sharedkernel`은 무변경.

## 사전 프로브 결과 (파일 이동 0)

advisor 권고대로 스펙 작성 전에 `com.kista.adapter`/`com.kista.application` package-info를 `Type.CLOSED`로 플립하고 `ModulithArchitectureTest`를 돌렸다. 파일은 하나도 옮기지 않았다.

1. **`RealtimeNotificationPort` 와일드카드 import는 죽은 코드다.** `trading/application/service`의 6개 파일(`TradingService`/`TradingReporter`/`ManualTradingService`/`CyclePositionPersistor`/`CycleRotationService`/`VrCycleRolloverService`)이 `import com.kista.application.port.output.*;`를 갖고 있으나, `RealtimeNotificationPort`를 이름으로 grep하면 0건이다. 옛 `HeartbeatPort`가 이 패키지에 있던 시절의 잔재 — `HeartbeatPort`는 이미 `com.kista.trading.application.port.output`으로 이전됐다. 따라서 SSE→notify 이동에 선결 이벤트 팬아웃 과제가 **필요 없다**.

2. **`SchedulerJobRunner`는 `NotifyPort`를 주입하고 `trading.domain.model.BatchContext`를 참조한다.** outbound-zero 인프라 모듈에 그대로 넣을 수 없다 — 디커플링 선결과제가 필요하다(아래 §선결과제). `SchedulerLockService`는 `JdbcTemplate`만 쓰므로 이미 outbound-zero, 그대로 이동 가능.

3. **CLOSED 플립은 전 모듈을 관통하는 순환 1개를 만든다.** 원인은 100% 예측 가능한 것뿐이었다 — 공유 인프라 fan-in(`BaseAuditEntity`/`BaseCreatedAtEntity`를 상속하는 ~20개 Entity, `AesCryptoService`/`AccountNoHasher`를 주입하는 3개 PersistenceAdapter, `SchedulerJobRunner`/`SchedulerLockService`를 주입하는 9개 스케쥴러) + 앱셸 컨트롤러 8개. market~strategyconfig 이전에서 반복됐던 "물리 이전 후에야 드러나는 숨은 전이 순환"은 이번 프로브에서 발견되지 않았다. 단 프로브는 **파일 이동 후** 다시 돌려야 최종 확증된다(§게이트).

## 대상 인벤토리와 목적지

### `com.kista.adapter`

| 현재 위치 | 파일 | 크로스모듈 실측 | 목적지 |
|---|---|---|---|
| `in/web/GlobalExceptionHandler` | 1 | account·broker·finance·user·trading·privacy "domain" + admin "port" 예외 매핑 | `com.kista.web` |
| `in/web/MetaController` | 1 | finance·strategyconfig "domain" + trading "domain"(`CycleOrderStrategies`) + sharedkernel enum 집계 (`GET /api/meta` enum SSOT) | `com.kista.web` |
| `in/web/TradingCycleController` | 1 | strategyconfig `StrategyUseCase` + stats `AccountStatisticsUseCase` + trading `TradingExecutionUseCase`/`VrReconfigureUseCase` — 3모듈 오케스트레이터 | `com.kista.web` |
| `in/aop/ErrorLogAspect` | 1 | admin `AppErrorLogPort` (cross-cutting) | `com.kista.web` |
| `in/web/DashboardController` | 1 | stats `AccountStatisticsUseCase` **단독** | `com.kista.stats.adapter.in.web` |
| `in/web/StatisticsController` | 1 | stats `AccountStatisticsUseCase` + sharedkernel `StrategyTicker` | `com.kista.stats.adapter.in.web` |
| `in/web/TossStatisticsController` | 1 | stats `TossStatisticsUseCase` + sharedkernel `StrategyTicker` | `com.kista.stats.adapter.in.web` |
| `in/web/openapi/HousingBenchmarkOpenApiCustomizer` | 1 | stats (주택 벤치마크 문서화) | `com.kista.stats.adapter.in.web.openapi` |
| `in/web/FcmController` | 1 | notify `FcmDeviceTokenPort` | `com.kista.notify.adapter.in.web` (신설) |
| `in/web/TradeStreamController` | 1 | `TradeSseEmitterRegistry` (concrete) | `com.kista.notify.adapter.in.web` |
| `out/sse/SseEmitterRegistry` | 1 | `implements RealtimeNotificationPort`; 소비: user `AuthController`, notify `TradingReportNotifier` | `com.kista.notify.adapter.out.sse` |
| `out/sse/TradeSseEmitterRegistry` | 1 | 소비: `TradeStreamController`, notify `TradingReportNotifier` | `com.kista.notify.adapter.out.sse` |
| `out/marketdata/CommonMarketPriceFeed` | 1 | 구현 `broker/TossPriceApi`, 소비 `broker/MockBrokerAdapter` — 둘 다 broker 내부 | `com.kista.broker.adapter.out.marketdata` (internal, NI 불필요) |
| `out/crypto/AesCryptoService`, `out/crypto/AccountNoHasher` | 2 | 소비: user·finance·account persistence | `com.kista.platform.crypto` |
| `out/persistence/BaseAuditEntity`, `BaseCreatedAtEntity`, `JpaAuditingConfig` | 3 | 상속·주입: ~10개 모듈 Entity | `com.kista.platform.persistence` |
| `in/schedule/SchedulerJobRunner`, `SchedulerLockService` | 2 | 주입: user·finance·market·trading·stats 스케쥴러 9곳 | `com.kista.platform.scheduling` (선결과제 후) |
| `in/web/dto/*` | ~30 | 대부분 컨트롤러 동행. 예외: `MarketSessionResponse`·`TossCandleResponse`가 market `MarketHolidayController`와 (stats로 갈) `TossStatisticsController` **양쪽**에서 소비 | 컨트롤러 따라 이동 + §DTO 이중 소유 |
| `package-info` | 1 | — | 삭제 |

### `com.kista.application`

| 파일 | 목적지 |
|---|---|
| `port/output/RealtimeNotificationPort` | `com.kista.notify.application.port.output` (notify 기존 "port" NamedInterface 합류) |
| `config/MetricsConfig` | `com.kista.web.config` (앱 레벨 `@Configuration`, 소비자 0) |
| `package-info` | 삭제 |

## 설계

### 1. `com.kista.web` — CLOSED inbound sink

```
com.kista.web/
  package-info.java          @ApplicationModule(type = CLOSED)  — NamedInterface 0개
  GlobalExceptionHandler.java
  MetaController.java
  TradingCycleController.java
  config/MetricsConfig.java
  aop/ErrorLogAspect.java
  dto/                       — 위 컨트롤러가 쓰는 것만
```

**CLOSED인데 모든 모듈 NamedInterface로 fan-out해도 안전한 이유:** 컨트롤러/`@ControllerAdvice`/`@Aspect`는 아무 모듈도 import하지 않는다(inbound 어댑터는 순수 sink). sink 모듈은 다른 모든 모듈을 참조해도 순환에 참여할 수 없다 — 이건 loophole이 아니라 구조적 성질이다. `GlobalExceptionHandler`/`MetaController`/`TradingCycleController`의 모든 import를 각 모듈의 공개 NamedInterface와 대조했고 전부 노출된 표면에 착지한다(`account`/`finance`/`user`/`broker`/`trading`/`privacy` "domain", `admin` "port", `strategyconfig`+`stats`+`trading` "usecase", `sharedkernel` OPEN). 파일 이동 후 프로브로 재확증한다.

**`TradingCycleController`가 여기인 이유:** `/api/trading-cycles/**`는 단일 REST 리소스인데 strategyconfig(전략 설정)·stats(사이클 이력)·trading(실행) 3모듈을 오케스트레이션한다. trading 모듈에 넣으면 trading은 현재 stats·strategyconfig import가 0인데 `stats→trading`이 이미 존재하므로 `stats↔trading` 순환을 제조한다. 리소스를 3모듈로 쪼개 "소유권"을 맞추는 건 하나의 REST 리소스를 파편화하는 것이라 더 나쁘다. 3모듈 오케스트레이터 = composition-root 관심사이며, 그게 `com.kista.web`의 존재 이유다.

**`MetricsConfig`가 `web`인 이유:** `@Configuration` 빈으로 소비자가 0이다. 앱 레벨 관측 설정이라 앱셸이 자연스러운 집. (별도 최상위 `config` 패키지를 만들 이유는 없음 — YAGNI.)

### 2. `com.kista.platform` — OPEN + outbound-zero 불변식

```
com.kista.platform/
  package-info.java          @ApplicationModule(type = OPEN)
  persistence/               BaseAuditEntity, BaseCreatedAtEntity, JpaAuditingConfig
  crypto/                    AesCryptoService, AccountNoHasher
  scheduling/                SchedulerJobRunner, SchedulerLockService
```

**`common`에 병합하지 않는 이유:** `com.kista.common`은 문서상 Spring/JPA 무의존이고 도메인 코드가 이 패키지에 닿는다([[constraints]] — `UsTradeDates` 등). `BaseAuditEntity`(`@MappedSuperclass`)/`JpaAuditingConfig`(`@EnableJpaAuditing`)/`AesCryptoService`(`@Component`)는 JPA/Spring 바인딩이다. 병합하면 도메인 코드가 실수로 JPA base class에 닿을 수 있다. 둘을 분리 유지한다.

**OPEN인데 안전한 이유 — `sharedkernel` 패턴 복제:** `sharedkernel`은 `Type.OPEN`이되 `HexagonalArchitectureTest.sharedkernel_must_not_depend_on_other_modules` ArchUnit 규칙이 outbound reference 0을 **강제**한다. `platform`도 동일하게:

```java
@Test
@DisplayName("platform은 common 외 다른 com.kista 모듈에 의존하지 않는다 — 인프라 leaf 불변식")
void platform_must_not_depend_on_other_modules() {
    ArchRule rule = noClasses()
            .that().resideInAPackage("com.kista.platform..")
            .should().dependOnClassesThat(
                    resideInAPackage("com.kista..")
                            .and(resideOutsideOfPackage("com.kista.platform.."))
                            .and(resideOutsideOfPackage("com.kista.common..")));
    rule.check(classes);
}
```

`platform→common`만 허용(예: `SchedulerLockService`가 `TimeZones`를 쓸 수 있음). OPEN은 outbound-zero를 **증명**할 때만 안전하다 — 주석으로 주장하는 게 아니라.

### 3. SSE → notify 통합

notify는 이미 Telegram(관리자봇)·FCM(사용자 푸시) 발송을 소유한다. SSE는 같은 종류의 3번째 사용자 대면 발송 채널이다.

| 이동 | from → to |
|---|---|
| `SseEmitterRegistry` | `com.kista.adapter.out.sse` → `com.kista.notify.adapter.out.sse` |
| `TradeSseEmitterRegistry` | `com.kista.adapter.out.sse` → `com.kista.notify.adapter.out.sse` |
| `RealtimeNotificationPort` | `com.kista.application.port.output` → `com.kista.notify.application.port.output` ("port" NamedInterface 합류) |
| `TradeStreamController` (`/api/trades/stream`) | `com.kista.adapter.in.web` → `com.kista.notify.adapter.in.web` |
| `AuthController`의 `/api/auth/status-stream` 핸들러 | `com.kista.user.adapter.in.web.AuthController` → `com.kista.notify.adapter.in.web` 신규 컨트롤러 |

- **`sse` 경로 세그먼트 유지 필수.** `HexagonalArchitectureTest.sse_emitter_registry_must_not_be_used_in_application_layer` 규칙이 `com.kista..adapter.out.sse..`로 키잉돼 있다. `notify.adapter.out.sse`로 옮기면 규칙이 계속 매칭된다. `notify.adapter.out.gateway`로 옮기면 규칙이 **조용히 매칭을 멈추고** 불변식이 green build와 함께 증발한다.
- **`RealtimeNotificationPort`의 `TradeEvent` 파라미터:** `notify→trading` 모듈 엣지가 이미 존재하므로(notify가 trading 이벤트 구독) `trading.domain.model.TradeEvent`를 포트 시그니처에 두는 건 순환이 아니다.
- **두 SSE 엔드포인트를 notify로 옮기는 이유:** `user/AuthController`가 `SseEmitterRegistry`를, `TradeStreamController`가 `TradeSseEmitterRegistry`를 **concrete 클래스로** 주입한다(포트 아님). 레지스트리가 notify-internal이 되면 두 주입 모두 위반이다. 엔드포인트째 `notify.adapter.in.web`로 옮기면 경로가 동일하게 유지되고(kista-ui 무영향) notify가 모든 사용자 대면 발송을 일관되게 소유한다. 대안(notify가 `SseEmitter`를 반환하는 포트 노출)은 Spring Web 타입을 포트 시그니처에 끌어들이므로 피한다.
- **죽은 와일드카드 import 제거:** 위 6개 trading 서비스의 `import com.kista.application.port.output.*;`를 삭제한다(프로브에서 미사용 확증).
- `AuthController`에서 status-stream 핸들러만 분리하면 나머지 인증 핸들러는 그대로 user에 남는다. 새 `notify` 컨트롤러는 `SseEmitterRegistry.connect(userId)` 한 줄만 호출.

### 4. 단일 소유자 이동 (저위험)

- **통계 컨트롤러 3종** (`Dashboard`/`Statistics`/`TossStatistics`) + 전용 dto → `com.kista.stats.adapter.in.web(.dto)`. stats는 이미 이 패키지를 internal로 갖고 있다(`StatsController`/`BacktestController` + dto 9종). 세 컨트롤러가 소비하는 usecase(`AccountStatisticsUseCase`/`TossStatisticsUseCase`)는 이미 stats 소유.
- **`HousingBenchmarkOpenApiCustomizer`** → `com.kista.stats.adapter.in.web.openapi`.
- **`CommonMarketPriceFeed`** → `com.kista.broker.adapter.out.marketdata`. 구현자 `TossPriceApi`와 소비자 `MockBrokerAdapter`가 둘 다 broker 내부라 NamedInterface 불필요(broker adapter/out은 의도적 비공개).
- **`FcmController`** + `FcmTokenRequest` → `com.kista.notify.adapter.in.web(.dto)` (신설 패키지, `TradeStreamController`와 공유).

### 5. DTO 이중 소유

플랜 작성 시 grep으로 정밀 재확인한 결과, 이중 소유는 2건이다(스펙 초안의 `MarketSessionResponse`는 오기 — `MarketHolidayController` 단독):

- **`CycleHistoryPageResponse` + 중첩용 `CycleHistoryResponse`** — `DashboardController`(→stats) + `TradingCycleController`(→web). 둘 다 `trading.domain.model.{CycleHistoryPage,CyclePositionHistoryEntry}`만 소비.
- **`TossCandleResponse`** — `TossStatisticsController`(→stats) + `market.adapter.in.web.MarketHolidayController`. `broker.domain.model.toss.TossCandle` 소비.
- **`MarketSessionResponse`** — `MarketHolidayController` 단독 → `market.adapter.in.web.dto`로 단순 이동.

**이중 소유 해법은 복제다** — 각 소유 모듈의 internal `adapter.in.web.dto`에 byte-identical 사본. 이 코드베이스에 이미 11개의 own-type 복제 선례가 있다([[constraints]] "모듈 경계 포트 시그니처"). 응답 DTO에 공유 집을 찾으려 하지 않는다. 세 DTO는 순수 데이터 홀더(`from()`/`fromList()` 팩토리 + record 필드)라 복제 비용이 낮다.

### 선결과제: `SchedulerJobRunner` 디커플링

`SchedulerJobRunner`는 (a) `NotifyPort`를 주입해 "X 시작"/"X 완료"/"X 오류" 관리자 텔레그램 핑을 보내고, (b) `run(String, Supplier<List<BatchContext>>, Action)` 오버로드에서 `trading.domain.model.BatchContext`를 제네릭 타입 인자로 참조한다. 둘 다 outbound-zero `platform`과 충돌한다.

- **(a) `NotifyPort` → 이벤트 팬아웃.** `SchedulerJobRunner`가 `SchedulerLifecycleEvent(String name, Phase phase, Throwable error)` (`Phase {STARTED, COMPLETED, FAILED}`)를 발행하고, notify에 `SchedulerNotifier`가 `@TransactionalEventListener(fallbackExecution = true)` 또는 `@EventListener`로 구독해 `notifyInfo`/`notifyError`를 호출한다. 이 코드베이스에 검증된 패턴 5번째 인스턴스(`MarketAlertNotifier`/`PrivacyAlertNotifier`/`StatsAlertNotifier`/`TradingAlertNotifier` 선례) — EPR(`event_publication` 테이블)로 추적돼 재기동 시 실패분 자동 재시도. 이벤트 클래스는 `com.kista.platform.scheduling`에 위치(notify가 platform 이벤트를 구독 = `notify→platform` leaf 엣지, 순환 아님).
  - 인터럽트 처리 주의: `InterruptedException` catch 절이 `notifyError(e)` 후 rethrow하는 기존 동작(락 즉시 해제 목적)을 이벤트 발행이 **동기 완료**한 뒤 rethrow하도록 유지. `@TransactionalEventListener`는 트랜잭션 밖 스케쥴러에서 안 붙을 수 있으니 `fallbackExecution=true` 필수, 또는 `@EventListener` 동기 처리.
- **(b) `BatchContext` → 제네릭.** `run(String, Supplier<List<BatchContext>>, Action)` → `<T> void run(String, Supplier<List<T>>, Action<T>)`. runner는 `contexts.size()`를 로그에만 쓰고 나머지는 `Action`에 그대로 넘긴다 — 제네릭으로 충분. `Action` `@FunctionalInterface`도 `Action<T>`로.

이건 정리 작업이 아니라 **선결 태스크**다(다른 이동이 platform을 정의하기 전에 완료돼야 함).

## 시퀀스 (SDD)

| # | 태스크 | 게이트 | 병렬 |
|---|---|---|---|
| 0 | ✅ 프로브 (CLOSED 플립 + 와일드카드 확인) — 완료, 되돌림 | — | — |
| 1 | `SchedulerJobRunner` 디커플링: `SchedulerLifecycleEvent` + notify `SchedulerNotifier` + 제네릭 `<T>` | compile + `SchedulerJobRunnerTest` 있으면 갱신 + notify 리스너 테스트 | 선결 (2에 앞섬) |
| 2 | `com.kista.platform` 패키지 신설 + `platform_must_not_depend_on_other_modules` ArchUnit 규칙 + `persistence`/`crypto`/`scheduling` 이동 + 소비자 ~35개 import 재작성 (로직 0) | `HexagonalArchitectureTest` + compile | 3·4와 파일 분리 |
| 3 | 단일 소유자 이동: 통계 컨트롤러 3종 + dto → stats, `HousingBenchmarkOpenApiCustomizer` → stats, `CommonMarketPriceFeed` → broker, `FcmController` → notify | compile + 해당 `*ControllerTest` 패키지 이동 | 4·5와 분리 |
| 4 | DTO 이중 소유: `MarketSessionResponse`·`TossCandleResponse` market/stats 각각 복제, `MarketHolidayController` import 갱신 | compile | 3과 함께 |
| 5 | SSE 통합 → notify: 레지스트리 2 + `RealtimeNotificationPort` + `TradeStreamController` + `AuthController` status-stream 분리 + 죽은 와일드카드 import 6개 제거 + `sse_emitter_registry_*` ArchUnit 규칙 경로 갱신 | `HexagonalArchitectureTest` + `AuthControllerTest`/SSE 테스트 + compile | 1에 의존 아님(프로브로 독립 확인됨), 3·4와 분리 |
| 6 | `com.kista.web` 앱셸: `GlobalExceptionHandler`/`MetaController`/`TradingCycleController`/`ErrorLogAspect`/`MetricsConfig` + dto 이동, `com.kista.adapter`/`com.kista.application` package-info 3개 삭제 | 최후 | 단독 |
| 7 | 전체 게이트 | `HexagonalArchitectureTest` + `ModulithArchitectureTest`(`verify()`) + 전체 테스트 스위트 + 리뷰어 검수 | — |

- 2·3·4·5는 파일 분리돼 병렬 가능. 1은 2·5 앞. 6은 최후(shim이 비어야 삭제).
- 각 태스크 diff 단위로 리뷰어 검수 후 커밋. 태스크별 리뷰를 다 거치면 최종 전체 브랜치 리뷰는 생략 기본(교차 통합 위험은 7의 `verify()`가 커버) — 사용자 확인.

## kista-ui 영향

**없음.** 근거:
- 패키지 이동은 JSON 직렬화에 불가시 — 응답 body 형태 불변.
- SSE 엔드포인트 경로 불변(`/api/trades/stream`, `/api/auth/status-stream`).
- DTO `from()`/필드 형태 변경 없음.

유일한 파손 벡터: DTO의 `from()`이 이동 후 접근 불가해지는 도메인 타입을 파라미터로 받는 경우. §5(DTO 이중 소유)가 그 인스턴스이고 복제로 해결. 플랜에서 각 이동 DTO의 `from()` 파라미터 타입을 목적지 모듈의 가시 NamedInterface와 대조 감사한다.

## 비-목표 (YAGNI)

- `com.kista.common` 재편 — Spring 무의존 순수 유틸, 이미 정직. 무변경.
- `com.kista.sharedkernel` 변경 — 무변경.
- 별도 `com.kista.sse` 전용 모듈 — notify가 발송 채널을 이미 소유하므로 과잉.
- `RealtimeNotificationPort` 포트 역전/이벤트 전환 — 프로브로 `notify` 단독 소비 확인, 단순 물리 이동으로 충분.
- 스케쥴러 골격을 각 모듈로 분산 — 9개 소비자에 9벌 복제는 명백한 악수.
