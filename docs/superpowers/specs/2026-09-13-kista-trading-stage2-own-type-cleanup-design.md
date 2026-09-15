# kista-trading 2단계 own-type 뒤처리 — 설계

## 배경/목적

`2026-09-11-kista-trading-service-split-design.md`(원본 설계)의 2단계 D항은 own-type 신설을 "3단계로 미룬다"고 적으면서, 그 근거로 `:api → :trading-core` 컴파일 의존이 2단계 동안은 유지되기 때문이라고 명시했다. 이 문서는 그 D항이 예고한 own-type 전환 중 **2단계가 이미 옮긴 admin/stats 조회 경로에 대한 뒤처리**만을 다룬다.

원본 설계의 "3단계"는 이보다 훨씬 크다 — 전략 CRUD·수동 실행·주문 취소·VR 재설정·계좌 CRUD를 내부 API로 전환하고, `user_notify_profile` 신설·Redis Stream 배선까지 포함해 `:api → :trading-core` 컴파일 의존을 완전히 0으로 만드는 단계다. 이 문서는 그 3단계 본체를 대체하지 않는다 — admin/stats가 이미 2단계에서 HTTP 경계로 넘어간 조회 지점들이 여전히 `trading.domain.model.Order`/`Strategy` 원본 클래스를 import하고 있는 것만 정리한다. 원본 D항이 예고한 "약 20개 파일" 교체 대상과 이 문서의 실측 결과가 거의 일치한다.

## 스코프 확정 근거

`com.kista.admin`/`com.kista.stats`(root `:api`) 전체를 grep해 `trading.domain.model.Order`/`Strategy`/`StrategySummary`/`matching.domain.model.OrderTiming` 참조 파일을 전수 조사했다. 결과 3개 그룹으로 갈렸다:

1. **죽은 import** (7개 파일) — import만 있고 실제 코드에서 그 타입을 쓰지 않음. `StrategyStatusRequest`/`RuntimeSettingsService`/`AdminReorderCommand`/`AdminManualTradeCorrectionCommand`/`AdminTradeCorrectionResult`/`AdminReorderRequest`/`AdminManualTradeCorrectionRequest` — 전부 Task 7/8(관리자 재정렬·정정 로직 trading-core 이관) 리팩토링 잔재.
2. **필드 타입만 참조** (nested enum 2종) — `Order.OrderStatus`(`AdminReorderResult`), `matching.domain.model.OrderTiming`(`AdminReorderCommand`/`AdminReorderRequest`). 둘 다 admin·trading이 공유하는 outbound-zero 값이라 `sharedkernel.OrderDirection`/`OrderType` 승격과 판정 구조가 동일하다.
3. **전체 클래스 참조** (own-type 신설 필요) — `TradingQueryPort`/`TradingQueryHttpAdapter`/`AdminQueryUseCase`가 `List<Order>`/`List<Strategy>`/`Map<UUID,StrategySummary>`를 그대로 반환하고, `AdminTradeResponse.from(Order,...)`/`AdminStrategyResponse.from(Strategy)`가 거의 전체 필드를 소비한다. `InvestmentPointsPort.Result.selectedStrategy`도 `Strategy` 전체를 필드로 갖지만 실제로는 id/type/ticker 3필드만 쓴다(`HousingBenchmarkComparisonBuilder.build()`가 즉시 `StrategyInfo`로 좁혀 변환).

`AdminStrategyService`(pause/resume)가 `trading.application.port.output.StrategyPort`를 직접 주입받아 HTTP조차 거치지 않고 원격 도메인 포트를 호출하는 것도 발견했으나, 이건 "전략 CRUD"로 원본 설계 3단계 본체가 명시한 항목이라 **이 문서 스코프에서 제외**한다 — 지금 손대면 3단계에서 전략 CRUD 전체를 설계할 때 이미 옮긴 pause/resume과 나머지 CRUD의 배선이 갈라져 재작업이 생긴다(원본 2단계 B항이 경고한 패턴과 동일). 아래 "미해결/후속"에 기록해 3단계 브레인스토밍이 이 발견을 다시 하지 않도록 한다.

`InvestmentPointsPort.Scope`(`STRATEGY`/`PORTFOLIO`)와 `BenchmarkScope`(`PORTFOLIO`/`STRATEGY`)는 값이 완전히 같은 중복 enum이다. 둘 다 root `:api`의 stats 모듈 소유라 own-type 문제가 아니라 순수 중복 — `StatsService`가 `InvestmentPointsPort.Scope.valueOf(scope.name())`으로 문자열 이어붙이기를 하는 게 그 증거다. trading-core 쪽 `InvestmentPointsQuery.Scope`(HTTP 서버 계약)는 그대로 둔다 — Gradle 컴파일 경계상 trading-core가 stats.domain.model을 참조할 수 없어 통합이 불가능하고, 이건 own-type 게이트 (a) 순환 불가피에 해당한다.

## 설계

### A. `sharedkernel.OrderStatus` 승격

`Order.OrderStatus`(PLANNED/PLACED/FILLED/PARTIALLY_FILLED/FAILED/CANCELLED)를 `com.kista.sharedkernel.OrderStatus`로 이동 — `OrderDirection`/`OrderType` 승격과 동일 패턴. `Order.status()` 필드 타입만 바뀌고 DB `@Enumerated(STRING)` 컬럼 상수명은 byte-identical 유지. 실측 참조 27개 파일(메인 11 + 테스트 16, `OrderEntity`의 `@Enumerated` 포함) — 규모상 독립 Task.

### A'. `sharedkernel.OrderTiming` 승격

`matching.domain.model.OrderTiming`(AT_OPEN/AT_CLOSE/IMMEDIATE)을 `com.kista.sharedkernel.OrderTiming`으로 이동. `matching`은 이미 `sharedkernel`을 허용 의존으로 참조하므로(`HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`가 `sharedkernel`·`privacy`만 허용) 이동 후에도 `matching` 내부 참조는 그대로 통과한다. 실측 참조 40개 파일(메인 17 + 테스트 23) — `CycleOrderStrategy` 계열 등 matching 커널 핵심 값이라 파급 범위가 A보다 넓다. 값 자체는 안 바뀌고 import 경로만 바뀌는 기계적 치환이지만 파일 수 때문에 독립 Task로 둔다.

### B. admin own-type read model 2종 신설

`com.kista.admin.domain.model`에 추가:

- `AdminOrderView` — `Order`의 15개 필드 **전체** 복제(`orderLeg` 포함). `TradingInternalQueryController`(trading-core 서버)가 `Order` 원본을 그대로 JSON 직렬화해 반환하므로, admin 쪽 역직렬화 타입에서 필드를 하나라도 빠뜨리면 `Jackson`의 `FAIL_ON_UNKNOWN_PROPERTIES` 설정에 의존하게 된다 — 그 설정을 확인하는 대신 전체 필드를 그대로 복제해 안전하게 간다.
- `AdminStrategyView` — `Strategy`의 5개 필드(id/type/status/ticker/cycleSeedType) 전체 복제. `AdminStrategyResponse.from()`이 5필드 모두 소비한다.

`StrategySummary`는 건드리지 않는다 — 코드 주석에 이미 "admin이 이 타입을 직접 소비(own-type 아님)"이라 명시된 기존 설계 결정이고, 2필드(strategyId/strategyType)뿐이라 narrowing 이득이 없다.

### C. 조회 경로 시그니처 교체

`TradingQueryPort`/`TradingQueryHttpAdapter`/`AdminQueryUseCase`의 반환 타입을 `List<Order>`→`List<AdminOrderView>`, `List<Strategy>`→`List<AdminStrategyView>`, `Map<UUID,List<Strategy>>`→`Map<UUID,List<AdminStrategyView>>`로 교체한다. `AdminQueryService`(구현체)와 `AdminTradeResponse.from()`/`AdminStrategyResponse.from()`/`AdminAccountResponse.from()`도 새 타입을 받도록 시그니처를 맞춘다. 서버 쪽(`TradingInternalQueryController`)은 무변경 — JSON 필드 shape이 이미 `AdminOrderView`/`AdminStrategyView`와 동일하므로 Jackson 역직렬화가 자동으로 맞는다.

### E. `StrategyRef` 최상위 승격

`HousingBenchmarkComparison.StrategyInfo`(nested record, `com.kista.stats.domain.model`)를 최상위 `StrategyRef(UUID id, StrategyType type, StrategyTicker ticker)`로 승격한다. `HousingBenchmarkComparison.strategy` 필드와 `InvestmentPointsPort.Result.selectedStrategy` 필드 양쪽이 이 타입을 공유 — 포트가 특정 응답 aggregate의 내부 타입에 묶이는 문제를 없앤다. `HousingBenchmarkComparisonBuilder.build()`가 지금 하는 `Strategy → StrategyInfo` 변환 3줄이 사라지고, `InvestmentPointsHttpAdapter`가 HTTP 응답을 역직렬화할 때 바로 `StrategyRef`로 받는다.

### F. `InvestmentPointsPort.Scope` 삭제

`InvestmentPointsPort.fetch()`의 `Scope` 파라미터 타입을 `BenchmarkScope`로 교체하고 nested `enum Scope`를 삭제한다. `StatsService`의 `InvestmentPointsPort.Scope.valueOf(scope.name())` 변환 코드가 사라진다. `InvestmentPointsHttpAdapter`는 `queryParam("scope", scope)`에 `BenchmarkScope` 값을 그대로 넘긴다(값 이름 STRATEGY/PORTFOLIO 동일이라 HTTP 쿼리스트링·서버 측 `InvestmentPointsQuery.Scope` 파싱에 영향 없음).

### G. 죽은 import 정리

위 "스코프 확정 근거"의 7개 파일에서 미사용 import를 제거한다.

## Global Constraints

- DB `@Enumerated(STRING)` 컬럼 상수명은 이동 전후 byte-identical 유지(OrderStatus/OrderTiming 둘 다).
- 각 태스크 완료 시 `./gradlew test` 전체 그린 + `ApplicationModules.verify()` 그린.
- `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`가 A' 이후에도 그린인지 확인(sharedkernel은 이미 허용 의존이라 통과 예상이나 실측 확인 필수).
- A/A'(sharedkernel 승격)를 B/C(own-type read model)보다 먼저 완료한다 — `AdminOrderView`/`AdminStrategyView`의 status/timing 필드 타입이 sharedkernel 값이어야 하므로 순서 의존이 있다.

## 테스트

- 이 문서가 다루는 범위는 전부 조회 경로(브로커 호출 없음)라 실행/취소 이중 실행 문제가 없다 — 기존 2단계 검증 규율(옛 경로 vs 새 경로 값 비교)을 그대로 재사용 가능하나, 이번엔 응답 스키마 자체가 바뀌므로(own-type 필드 shape) 값 비교보다 **API 응답 계약 테스트**(각 필드가 원본 Order/Strategy 값과 일치하는지)가 더 적합하다.
- `AdminOrderView`/`AdminStrategyView` 역직렬화 테스트: `TradingQueryHttpAdapterTest`에 실제 서버 JSON(Order/Strategy 전체 필드 포함)을 목업으로 넣어 전체 필드가 손실 없이 매핑되는지 확인.

## 미해결/후속

- `AdminStrategyService`가 `trading.application.port.output.StrategyPort`를 직접 주입받아 pause/resume에서 원격 도메인 포트를 HTTP 없이 직접 호출한다 — 이건 원본 설계 3단계 본체의 "전략 CRUD" 항목이다. 전환 시 클래스 레벨 `@Transactional`을 제거하고(현재 이 클래스에 붙어 있음), `AdminReorderService` 패턴대로 HTTP 호출 후 `auditLogPort.log()`를 별도 호출해야 한다(`@Transactional` 내부에서 외부 HTTP 호출 금지 원칙 위반 방지). 3단계 브레인스토밍 시 이 발견을 재조사하지 말 것.
