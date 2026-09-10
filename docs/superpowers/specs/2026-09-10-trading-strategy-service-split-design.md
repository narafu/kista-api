# TradingService/StrategyService God Class 분리 설계

## 배경

kista-api 클린코드 리펙토링 스캔(4개 서브에이전트 병렬 실행) 결과, `TradingService`(577줄)와
`StrategyService`(660줄, 15필드)가 리스크 크고 설계 판단 필요한 항목으로 별도 세션에 인계됐다.

조사 결과 두 클래스의 실제 문제는 서로 다르다:

- **TradingService**: 이미 협력자 15개(생성자 인자)를 주입받는 얇은 오케스트레이터다. "책임 과다"가 아니라
  `executeBatch`/`placeOpenOrders` 두 파이프라인이 같은 후보수집·예산배정 절차를 거의 그대로 반복하고,
  `runSafely`/`notifyErrorSafely`/`notifyBatchInterrupted` 같은 공통 가드 로직이 클래스 안에 섞여 있는 것이
  진짜 문제다.
- **StrategyService**: `docs/agents/architecture.md`에 기존 결정이 있다 — "`StrategyUseCase`는
  command/query 미분리 유지 — `toDetail`/`assemble`을 command·query가 공유해 서비스 분리 시 헬퍼 중복만
  생긴다. 분리 트리거: query만 필요한 2번째 소비자 등장 시." 이 결정은 `update()`/`getById()`/`list*()`처럼
  `toDetail`/`assemble`을 실제로 공유하는 메서드 군에만 적용된다. `register()`(전체 등록 로직)와
  `strategySeedPreview`/`getByStrategy`/`getOrdersByStrategy`(조회 3종)는 grep 확인 결과
  `toDetail`/`assemble`을 전혀 호출하지 않는 독립 블록이라 이 결정과 무관하게 분리 가능하다.

`TradingOrderExecutor.placeOrders`/`placeAtOpenOrders`도 BUY 가격 캡 디스패치 로직이 완전히 중복돼 있어
같은 작업에서 함께 정리한다.

## 검토한 접근

1. **원 인계메시지의 schedulePipeline/executePhase 제네릭 추상화** — 기각. 실제 중복은
   `collectCycleCandidate`/`saveAllocatedOrders` 등 이미 공유 private 메서드로 흡수돼 있어 얇고,
   호출부가 2곳(executeBatch, placeOpenOrders)뿐이라 제네릭 Phase 추상화를 새로 만들면 과설계.
2. **최소 대응(TradingOrderExecutor 중복만 수정)** — TradingService는 이미 잘 분해돼 있다는 판단 하에
   보류하는 안. 하지만 `runSafely` 계열 가드 로직 중복 위험(StrategyService의 toDetail 사례와 동일 패턴)이
   실재해 완전히 보류하기엔 아쉬움.
3. **확인된 중복·응집 단위만 외과적으로 추출 (채택)** — 아래 설계.

## 아키텍처 / 컴포넌트

전부 `com.kista.trading.application.service` 패키지 내부(package-private) 신설 — 모듈 경계·NamedInterface
영향 없음(이 패키지는 이미 internal).

### 1. `TradingBatchGuard` (신규)
- `runSafely(String phase, BatchContext ctx, ThrowingSupplier<T> supplier)` — 기존 `TradingService.runSafely`
  그대로 이전 (InterruptedException 재throw, 나머지 예외는 로그+알림 후 `Optional.empty()`)
- `notifyErrorSafely(BatchContext ctx, Exception e)`
- `notifyBatchInterrupted(List<BatchContext> contexts)`
- 필드: `ApplicationEventPublisher`만
- `TradingService`와 `TradingCandidatePlanner`가 함께 주입받아 사용 — 가드 로직 중복 방지

### 2. `TradingCandidatePlanner` (신규)
- 이전 대상: `planAll`, `collectCycleCandidate`, `buildCycleStateFromExistingOrders`,
  `filterCreatableOrders`, `validateConcreteOrderLegs`, `saveAllocatedOrders`
- record 이전: `CycleState`, `CyclePlanCandidate`, `SaveAllocationResult` (package-private로 승격해
  `TradingService`도 참조 가능하게)
- 필드: `orderPort`, `orderComputer`, `orderPlanner`, `priceCapper`, `cycleOrderStrategies`,
  `budgetAllocator`, `balanceLoader`, `TradingBatchGuard` — `privacyTradePort`는 `loadPriceContext`에서만
  쓰이므로(`TradingPriceFetcher`로 함께 이전) 이 클래스엔 불필요
- `executeBatch`(AT_CLOSE 스코프)와 `placeOpenOrders`(AT_OPEN 스코프) 양쪽에서
  `planAll(contexts, timings, ...)` 형태로 호출 — 기존 `creatableTimings` 파라미터 그대로 재사용해 분기

### 3. `TradingOrderExecutor` 내부 리팩터 (기존 파일 수정, 신규 클래스 아님)
- `placeOrders`/`placeAtOpenOrders`의 캡 디스패치(`PriceCapMode` 3분기) 로직을
  `private void applyCap(boolean atOpen, LocalDate date, Account account, UUID cycleId, BigDecimal price,
  InfinitePosition position, VrPosition vrPosition, Strategy strategy)` 로 통합
- finder 호출(`findPlannedByCycleAndDate` vs `findAtOpenPlannedByCycleAndDate`)만 각 public 메서드에 남김

### 4. `TradingPriceFetcher` 내부 확장 (기존 파일 수정)
- 이전 대상: `loadPriceContext`, `reloadPlacementPrices`, `selectPriceAccount` + `PriceContext` record
- 이미 가격 조회를 전담하는 클래스라 자연스러운 소유처 — 신규 클래스 불필요

### 5. `StrategyCreationService` (신규)
- 이전 대상: `register()` 전체 + 전용 private 헬퍼 전부
  (`validateBootstrapPosition`, `resolveVrValue`, `resolveScheduledStart`, `fetchMarketPrice`,
  `resolveCreationSettings`, `validateVrCommand`, `normalizeVrRampParams`, `normalizeMoney`,
  `validateUniqueTicker`, `validateBalanceIfRequired`, `saveStrategyWithVersion`,
  `saveInitialCycleAndPosition`, `calcFreeCash`)
- record 이전: `SavedStrategyAndVersion`, `InitialCycleResult`, `VrRampParams`
- `StrategyService`가 `StrategyUseCase.register()`에서 이 클래스로 위임 (인터페이스 구현체는
  `StrategyService` 그대로 유지)

### 6. `StrategyHistoryQueryService` (신규)
- 이전 대상: `strategySeedPreview`, `getByStrategy`, `getOrdersByStrategy`, `resolveHistoryFrom`, `resolveHistoryTo`
- `StrategyService`가 해당 `StrategyUseCase` 메서드 3개에서 위임

### `StrategyService` 잔존 범위
`delete`/`pause`/`resume`/`listByUserId`/`listByAccountId`/`getById`/`update`/`toDetail`/`toDetails`/`assemble`
— architecture.md의 "command/query 미분리" 결정이 실제로 적용되는 유일한 응집 단위. `updateSeed`도
`update()`에서만 쓰여 여기 잔존.

## 데이터 흐름 변화

- `TradingService.executeBatch`/`placeOpenOrders`: 최상위 오케스트레이션(시장 개장 확인 → 시작예정일 필터 →
  가격 컨텍스트 로드 → 대기 → **candidatePlanner.planAll(...) 한 호출** → 접수 → 대기 → 리포트)만 남는다.
  `placeAll`/`reportAll`은 `TradingParallelRunner`와 강결합돼 있어 그대로 유지.
- `StrategyUseCase` 외부 계약(트랜잭션 전파 속성 포함)은 변경 없음 — `register`/`strategySeedPreview`는
  기존과 동일하게 `@Transactional(propagation = NOT_SUPPORTED)`을 위임 대상 클래스에 유지.

## 에러 처리

동작 변경 없음 — 기존 `runSafely`/`notifyErrorSafely`/`notifyBatchInterrupted` 시맨틱을 그대로 이전한다.
전략별 실패 격리(계좌 간 격리, InterruptedException만 예외적으로 재throw)는 이전 후에도 동일하게 유지돼야 한다.

## 테스트

- 이 작업은 순수 구조 리팩터(behavior-preserving) — 테스트 스위트가 스펙 역할.
- `TradingServiceTest`: 생성자 인자 재배선(`TradingCandidatePlanner`/`TradingBatchGuard` mock 추가,
  이전된 협력자 인자 제거) 외 **assertion 변경 금지**. 변경이 필요해지면 그 자체가 회귀 신호.
- `StrategyServiceTest`: 동일 원칙 — `StrategyCreationService`/`StrategyHistoryQueryService` mock 주입으로
  생성자만 재배선.
- 최종 검증: `./gradlew test --tests 'com.kista.trading.*'` 최종 1회 (전체 스위트 아님, CLAUDE.md
  "빌드/테스트 전체 스위트는 최종 1회만" 원칙 적용).
