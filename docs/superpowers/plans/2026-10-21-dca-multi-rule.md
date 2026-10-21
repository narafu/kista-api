# DCA 다중 규칙 + DB 원장 Implementation Plan (Plan 2/2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** DCA 전략 하나에 매수·매도 규칙을 여러 개 둔다. 보유와 평단은 DB 원장(`cycle_position`)으로 관리한다. 주식과 크립토가 이 구조를 함께 쓴다. DCA 주식 경로는 `CycleOrderStrategy.plan()` 정규 경로로 편입한다(R2). DCA를 통계·프리뷰에 포함하고, 수동 체결 보정을 연다.

**Architecture:**
- 매칭 커널 `DcaRule`이 기존 `DcaConfig`를 대체한다. 규칙 1개는 방향, 주기, 시점, 조건, 규모, 부족 정책으로 이루어진다.
- `DcaCycleOrderStrategy.decideRule()`이 규칙 1개를 판정한다. 주식 배치와 크립토 루프가 이 커널을 같이 쓴다.
- 주식은 `CycleOrderComputer`가 `PlanContext.DcaInputs`를 채워 `plan()`을 부른다. 그 결과 `TradingCandidatePlanner`의 `isDca()` 분기가 사라진다. 크립토는 실행 루프(`CryptoDcaRoundExecutor`)를 유지한다.
- 원장 갱신 방식은 주식과 크립토가 다르다.
  - 주식: 마감 리포트에서 자기 주문 체결로 갱신한다. Plan 1이 이미 이렇게 만들었다.
  - 크립토: 체결이 확정될 때 갱신한다.

**Tech Stack:** Java 25, Spring Boot 4, JPA/Flyway, JUnit 5, AssertJ, Mockito

**Spec:** `docs/superpowers/specs/2026-10-21-dca-multi-rule-ledger-design.md` (전체)

**전제:** Plan 1(`2026-10-21-ledger-decimal-own-fills.md`)이 main에 병합되어 두 role 모두에 배포되어 있어야 한다. 이 plan이 기대는 Plan 1의 결과는 다음과 같다.
- `AccountBalance.holdings`가 BigDecimal이다.
- `wholeShares()`가 있다.
- `Quantities`가 `com.kista.shared.kernel`에 있다.
- 모든 전략의 원장이 자기 주문 체결만 반영한다.

## Global Constraints

- 규칙은 전략당 1~10개, 조건은 규칙당 최대 10개(`DcaCondition` 기존 검증)다.
- 크립토 규칙: `orderType=MARKET`, `executionHour` 0~23이 필수다. AMOUNT는 원 단위 정수이고 5,000 이상이어야 한다.
- 주식 규칙: `orderType`은 MOO 또는 MOC이고, `executionHour`는 무시(null 저장)한다. 주말 요일은 금지다.
- `HOLDING_PERCENT`는 SELL 규칙에서만 쓸 수 있고 `0 < percent ≤ 100`이다. AMOUNT는 `amount > 0`이다.
- 주문 leg는 `"DCA_" + ruleKey`다. ruleKey는 UUID 문자열(36자)이고, `orders.order_leg varchar(50)`에 들어간다.
- 계좌+종목당 전략은 1개다. DCA 예외는 철회한다.
- DCA의 `usd_deposit`은 항상 0이다. 시드를 점유하지 않는다.
- 같은 거래일·같은 시점에 발동한 규칙은 전부 각자 주문한다. 크립토 같은 틱에서는 SELL 규칙을 먼저, BUY 규칙을 나중에 처리한다(같은 방향 안에서는 입력 순서). 지갑에 매도 대금을 더하지 않는다.
- 매도 가용량은 `min(원장 보유, 브로커 판매가능수량 − 같은 계좌·거래일·종목 PLANNED/PLACED SELL 예약분 − 같은 배치에서 앞 규칙이 계획한 SELL)`이다.
- 구 단일 규칙 API 필드는 제거하고 호환 기간을 두지 않는다.
- trading-core 마이그레이션은 다음 번호 `V12__dca_multi_rule.sql`이다(Plan 1이 V11). 이미지 롤백은 불가하다.
- 주석은 `//` 인라인만 쓴다. 테스트는 서브프로젝트를 지정해서 실행한다. 전체 스위트는 마지막 Task에서 1회만 돌린다. 커밋 author는 `narafu <narafu@kakao.com>`이다.

## Review Focus

1. **같은 날 규칙 편집.** ruleKey를 보존해서 수정하면 이미 판정되거나 주문된 규칙은 같은 거래일에 다시 발동하지 않아야 한다. ruleKey를 빼고 보내면 신규 규칙으로 처리한다(Task 2).
2. **크립토 같은 틱에서 SELL과 BUY가 동시에 발동하는 경우.** SELL 먼저, BUY는 매도 대금을 반영하지 않은 지갑 기준으로 처리한다. 두 규칙 모두 각자의 이력이 남아야 한다(Task 5).
3. **원장 보유보다 판매가능수량이 적은 경우.** PARTIAL이면 판매가능수량만큼, REJECT이면 `INSUFFICIENT_HOLDINGS`로 skip한다. 가용량이 0이면 정책과 무관하게 `NO_HOLDINGS`다(Task 1).
4. **크립토 체결 동기화 재실행.** 같은 주문이 두 번 settle돼도 원장에는 1회만 반영되어야 한다(Task 5).
5. **주식 같은 MOO에 BUY 규칙 2개와 예산 부족.** PARTIAL 규칙은 축소되고 REJECT 규칙은 빠진다. 빠진 규칙만 `INSUFFICIENT_BALANCE`로 이력이 남는다(Task 4).

---

### Task 1: 커널 — `DcaRule`·규모·부족 정책·규칙 판정 (추가만, 기존 경로 무변경)

**Files:**
- Create:
  - `shared/src/main/java/com/kista/shared/kernel/DcaSizingType.java` (`AMOUNT`, `HOLDING_PERCENT`)
  - `shared/src/main/java/com/kista/shared/kernel/DcaShortfallPolicy.java` (`PARTIAL`, `REJECT`)
- Modify: `shared/.../kernel/DcaSkipReason.java`. `INSUFFICIENT_HOLDINGS`를 추가한다(매도 REJECT 정책으로 거부된 경우).
- Create:
  - `trading-core/.../matching/domain/model/DcaRule.java`
  - `DcaSizing.java`
  - `DcaStrategyConfig.java`
  - `DcaRuleDecision.java`
- Modify: `trading-core/.../matching/domain/strategy/DcaCycleOrderStrategy.java`. `decideRule`를 추가하고, 기존 `decide`는 Task 3에서 지운다.
- Modify: `trading-core/.../matching/domain/strategy/DcaConditions.java`. `check`가 `DcaRule`도 받도록 오버로드하고, `Holding`은 원장 값을 받는다.
- Test:
  - `trading-core/src/test/.../matching/domain/model/DcaRuleTest.java`
  - `.../matching/domain/strategy/DcaRuleDecisionTest.java`

**Interfaces:**
- Produces:
  - `record DcaSizing(DcaSizingType type, BigDecimal amount, BigDecimal percent)`: type별로 amount와 percent를 배타 검증한다.
  - `record DcaRule(String ruleKey, OrderDirection direction, DcaFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth, OrderType orderType, Integer executionHour, DcaSizing sizing, DcaShortfallPolicy shortfall, List<DcaCondition> conditions)`
    - 메서드: `requireCompatible(Market)`, `boolean hasIndicator(DcaIndicator)`, `OrderTiming timing()`, `String orderLeg()`(`"DCA_" + ruleKey`), `DcaRule withRuleKey(String)`
    - `ruleKey`는 null을 허용한다. 저장 전 신규 규칙이 null이다. 검증은 기존 `DcaConfig` 규칙을 그대로 옮긴다.
  - `record DcaStrategyConfig(List<DcaRule> rules)`
    - 규칙 1~10개, non-null ruleKey 중복 금지.
    - `boolean hasIndicator(DcaIndicator)`(any), `void requireCompatible(Market)`
  - `sealed interface DcaRuleDecision`
    - `record Planned(String ruleKey, OrderDirection direction, BigDecimal quantity, BigDecimal notional, BigDecimal referencePrice, BigDecimal limitPrice)`
      - 주식 `quantity`는 정수다. `notional`은 크립토 BUY만 쓴다(주문 금액). `limitPrice`는 주식 BUY 캡가이고, 나머지 경우는 기준가다.
    - `record Skipped(String ruleKey, DcaSkipReason reason, String detail, BigDecimal referencePrice)`
  - `record DcaRuleInputs(DcaRule rule, Market market, List<BigDecimal> closes, AccountBalance ledger, BigDecimal sellableAfterReserved, BigDecimal fearGreed, BigDecimal currentPrice, BigDecimal availableCash)`
    - `sellableAfterReserved`는 SELL에만 있고, 조회 실패면 null이다.
    - `availableCash`는 크립토 BUY에만 있다(지갑 원화). 주식은 null이고 allocator가 판정한다.
  - `DcaRuleDecision DcaCycleOrderStrategy.decideRule(DcaRuleInputs in)`

- [ ] **Step 1: 실패 테스트 작성** (`DcaRuleDecisionTest`)

```java
@Test void 주식_보유비율_매도는_원장_보유의_비율_내림() { // ledger 10주, percent 25 → 2주 (floor 2.5)
    assertThat(planned(decide(stockSell(percent("25")), ledger("10"), sellable("10"))).quantity()).isEqualByComparingTo("2");
}
@Test void 원장보다_판매가능이_적으면_PARTIAL은_판매가능만큼() { // AMOUNT 1000 / 종가 50 = 20주, ledger 20, sellable 5 → 5
}
@Test void 원장보다_판매가능이_적으면_REJECT는_INSUFFICIENT_HOLDINGS() {}
@Test void 가용_0이면_정책무관_NO_HOLDINGS() {}
@Test void 크립토_BUY_REJECT는_원화부족시_INSUFFICIENT_BALANCE() { // amount 15000, availableCash 10000 → skip
}
@Test void 크립토_BUY_PARTIAL은_원화가용_수수료차감_금액으로() { // amount 15000, cash 10000 → notional = floor(10000/1.0005) = 9995
}
@Test void 크립토_보유비율_매도는_소수8자리_내림() { // ledger 0.00123457, 50% → 0.00061728
}
@Test void AVG_PRICE_조건은_원장_평단으로_판정() { // ledger avg 100, 종가 99, LTE 0% → 통과
}
@Test void 주식_BUY는_기존처럼_max전일종가현재가_캡가() { /* 기존 decide 테스트의 기대값 재사용 */ }
```

`DcaRuleTest`: HOLDING_PERCENT가 BUY 규칙에 오면 IAE. percent 0 또는 101이면 IAE. MARKET인데 executionHour가 null이면 IAE. 규칙 11개면 IAE(`DcaStrategyConfig`). ruleKey가 중복이면 IAE.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests '*DcaRule*' 2>&1 | grep -E "FAILED|BUILD|error:"` 실행 시 컴파일 실패가 나야 한다.
- [ ] **Step 3: 구현**
  - 판정 순서는 데이터 → 조건 → 보유(SELL) → 규모 → 부족 정책 → 최소 단위(`BELOW_ONE_SHARE`/`BELOW_MIN_ORDER_AMOUNT`)다.
  - 크립토 수수료 계수 `1.0005`와 5,000원 최소는 `CryptoDcaRoundExecutor`에 있던 상수를 커널로 옮긴다.
- [ ] **Step 4: 통과 확인** — Step 2 명령을 다시 실행해 BUILD SUCCESSFUL을 확인하고, 기존 `DcaCycleOrderStrategyTest`도 그대로 통과하는지 본다.
- [ ] **Step 5: Commit** — `feat(matching): DCA 규칙·규모·부족 정책과 규칙 단위 판정 커널을 추가한다`

---

### Task 2: 다중 규칙 저장·설정·등록·API 전환 (기존 실행 경로는 규칙 루프로 최소 적응)

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V12__dca_multi_rule.sql`
  1. 기존 DCA 데이터를 정리한다. 대상은 `strategy.type='DCA'`인 전략과 그 종속 행이다: `dca_execution`, `orders`(DCA cycle), `cycle_position`, `strategy_cycle`, `strategy_dca_condition`, `strategy_dca_version`, `strategy_version`, `strategy`. FK CASCADE를 확인한 뒤 명시적으로 DELETE한다.
  2. `DROP TABLE strategy_dca_condition`, `strategy_dca_version`
  3. `CREATE TABLE trading.strategy_dca_rule`
     - 컬럼: `id uuid pk`, `strategy_version_id uuid FK strategy_version ON DELETE CASCADE`, `rule_key varchar(36) NOT NULL`, `sort_order int`, `direction varchar(20)`, `frequency varchar(20)`, `day_of_week varchar(20)`, `day_of_month int`, `order_type varchar(20)`, `execution_hour smallint`, `sizing_type varchar(20)`, `amount numeric(20,2)`, `percent numeric(5,2)`, `shortfall_policy varchar(20)`, `created_at`
     - 제약: UNIQUE(`strategy_version_id`, `rule_key`), UNIQUE(`strategy_version_id`, `sort_order`)
     - CHECK: 기존 weekly·monthly·execution_hour 검사, `(sizing_type='AMOUNT') = (amount IS NOT NULL AND percent IS NULL)`, `percent > 0 AND percent <= 100`
  4. `CREATE TABLE trading.strategy_dca_condition`
     - `rule_id FK strategy_dca_rule ON DELETE CASCADE`
     - 나머지 컬럼과 CHECK는 V7과 같다.
  5. `ALTER TABLE trading.dca_execution ADD COLUMN rule_key varchar(36) NOT NULL`. 데이터는 1번에서 비웠다. 유일키는 `uq_dca_execution_strategy_rule_trade_date (strategy_id, rule_key, trade_date)`로 바꾼다.
  6. 헤더 주석에 "기존 DCA는 테스트 데이터 — 사용자 결정으로 정리, 이미지 롤백 불가"를 적는다.
- Replace: `StrategyDcaVersionEntity`를 `StrategyDcaRuleEntity`로 바꾼다. `StrategyDcaConditionEntity`(rule FK)와 각 JpaRepository, `StrategyDcaDetailPersistenceAdapter`도 같이 바꾼다.
- Modify: `DcaExecution`(+`String ruleKey`), `DcaExecutionEntity`, `DcaExecutionPort`, `DcaExecutionPersistenceAdapter`, `DcaExecutionRecorder`
- Modify: `StrategyDcaDetailPort`, `DcaConfigService`, `StrategyCreationService`(`validateUniqueTicker`에서 DCA 예외 제거, DCA 시작 원장), `StrategyCreationPersister`, `RegisterStrategyCommand`, `BootstrapPosition`
- Modify: `DcaSummary`·`DcaSummaryAssembler`. 규칙별 next와 last를 만든다.
- Modify: DTO
  - `DcaConfigRequest`는 `rules`를 받는다.
  - Create: `DcaRuleRequest`, `DcaRuleResponse`, `DcaSizingRequest`
  - `DcaResponse`, `DcaExecutionPageResponse.Item`(+ruleKey, direction), `TradingCycleRequest.initialHoldings`(BigDecimal)
- Modify: root `src/main/java/com/kista/api/web/MetaController.java`에 `DcaSizingType`과 `DcaShortfallPolicy`를 `toEnumMeta`로 추가한다.
- Modify(최소 적응): `DcaRoundPlanner`와 `CryptoDcaRunner`/`CryptoDcaRoundExecutor`는 `DcaStrategyConfig.rules()`를 돌며 `decideRule`을 호출하게 바꾼다. 중복 방지와 복구 키는 규칙 leg와 `(strategyId, ruleKey, tradeDate)`다. 주식 다중 주문은 `DcaRoundPlanner.plan`의 반환을 `List<Planned>`로 넓히는 수준에서 끝낸다. 구조 개편은 Task 3에서 한다.
- Delete: `DcaConfig.java`, 기존 `decide`, `DcaInputs`
- Test:
  - `DcaConfigServiceTest`, `StrategyCreationServiceTest`, `StrategyDcaDetailPersistenceAdapterDbTest`, `DcaExecutionPersistenceAdapterDbTest`
  - 기존 DCA 테스트(`DcaRoundPlannerTest`, `CryptoDcaRoundExecutorTest`)를 규칙 기반으로 고친다.
  - `TradingCycleControllerTest`, `MetaControllerTest`

**Interfaces:**
- Consumes: Task 1의 `DcaRule`, `DcaStrategyConfig`, `decideRule`
- Produces:
  - `StrategyDcaDetailPort`
    - `DcaStrategyConfig save(UUID strategyVersionId, DcaStrategyConfig config)`
    - `Optional<DcaStrategyConfig> findActiveByStrategyId(UUID strategyId)`
    - `Map<UUID, DcaStrategyConfig> findActiveByStrategyIds(Collection<UUID>)`
  - `record DcaExecution(UUID id, UUID strategyId, String ruleKey, LocalDate tradeDate, Status status, DcaSkipReason skipReason, UUID orderId, BigDecimal quantity, BigDecimal referencePrice, String detail, Instant createdAt)`
  - `DcaExecutionPort`
    - `boolean insertIfAbsent(DcaExecution)`
    - `Optional<DcaExecution> find(UUID strategyId, String ruleKey, LocalDate tradeDate)`
    - `Map<UUID, Map<String, DcaExecution>> findLatestByStrategyIds(Collection<UUID>)`(규칙별 최신)
    - `List<DcaExecution> findPage(UUID strategyId, int page, int size)`
    - `long count(UUID strategyId)`
  - `DcaExecutionRecorder`
    - `recordOrdered(BatchContext, LocalDate, String ruleKey, Order, BigDecimal referencePrice)`
    - `recordSkipped(BatchContext, LocalDate, String ruleKey, DcaSkipReason, String detail, BigDecimal referencePrice)`
    - `DcaRoundSkippedEvent`에 `ruleKey`와 `direction`을 추가하고, 알림 문구에 방향과 규칙 순번을 넣는다.
  - `DcaConfigService.updateConfig(UUID strategyId, UUID requesterId, DcaStrategyConfig requested)`
    - null ruleKey에는 `UUID.randomUUID().toString()`을 부여한다.
    - non-null ruleKey가 현재 활성 버전에 없으면 `IllegalArgumentException("존재하지 않는 규칙입니다")`(400)를 던진다.
    - 새 strategy_version을 만드는 기존 흐름은 유지하고, direction 변경 금지 가드는 제거한다.
  - `RegisterStrategyCommand.initialHoldings: BigDecimal`
  - `BootstrapPosition.validate(BigDecimal holdings, BigDecimal avgPrice, Market market) -> BigDecimal`
    - 주식에서 소수 보유면 IAE(400)다. 크립토는 소수 8자리까지 허용한다.
    - DCA 등록에서 `initialHoldings > 0`이면 `bootstrapSnapshot(usdDeposit=0)`을 만든다.
    - 이때 `strategy_cycle.start_amount`는 `initialHoldings × initialAvgPrice`(초기 투입 원가)다. Task 7의 원금 계산 기준이다.
  - API JSON은 spec §6 그대로다: `dca.rules[]{ruleKey, direction, frequency, dayOfWeek, dayOfMonth, orderType, executionHour, sizing{type, amount, percent}, shortfall, conditions[]}`

- [ ] **Step 1: 실패 테스트 작성**
  - `DcaConfigServiceTest`
    - `ruleKey_보존_수정은_같은_키를_유지한다`
    - `없는_ruleKey는_400`
    - `신규_규칙은_UUID_ruleKey를_받는다`
  - `StrategyCreationServiceTest`
    - `DCA도_같은_계좌_종목_두번째_등록은_거부`
    - `크립토_DCA_소수_초기보유로_시작원장_생성`
    - `주식_소수_초기보유는_400`
  - `DcaExecutionPersistenceAdapterDbTest`: `같은_전략_같은날_다른_규칙은_각각_기록`, `같은_규칙_같은날_두번째는_insertIfAbsent_false`
  - `DcaRoundPlannerTest`: `같은날_ruleKey_보존_편집후_재발동하지_않는다`(Review Focus 1)
  - `TradingCycleControllerTest`: `구_단일규칙_필드만_보내면_400`
- [ ] **Step 2: 실패 확인** — 해당 테스트를 지정해 실행하면 실패가 나야 한다.
- [ ] **Step 3: 구현** — 위 Files 목록대로 바꾼다.
- [ ] **Step 4: 통과 확인**
  - `docker compose up -d postgres`를 띄운다.
  - `./gradlew :trading-core:test --tests '*Dca*' --tests '*StrategyCreation*' --tests '*TradingCycleControllerTest' --tests '*TradingApplicationContextLoadTest' 2>&1 | grep -E "FAILED|BUILD"`
  - `./gradlew :test --tests '*MetaController*' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 5: Commit** — `feat(dca): DCA 전략을 다중 규칙으로 저장·설정·등록하고 회차 이력을 규칙 단위로 기록한다`

---

### Task 3: R2 — DCA 주식 경로를 `CycleOrderStrategy.plan()`으로 편입

**Files:**
- Modify: `trading-core/.../matching/domain/strategy/CycleOrderStrategy.java`
  - `PlanContext`에 `DcaPlanInputs dca`를 추가한다.
  - `OrderPlan`에 `List<DcaRuleDecision.Skipped> skips`와 `Map<String, BigDecimal> referencePrices`(leg → 기준가)를 추가한다. 기존 3-인자 생성자는 빈 값으로 유지한다.
- Modify: `DcaCycleOrderStrategy.plan(ctx)`가 `ctx.dca().dueRules()`마다 `decideRule`을 부른다. `UnsupportedOperationException`은 제거한다.
- Create: `trading-core/.../trading/application/service/DcaPlanInputCollector.java`
  - `DcaRoundPlanner`의 실행일·timing·당일 판정 여부·기존 주문 복구 로직을 옮긴다.
  - 일봉 캐시, 공포탐욕 TTL 메모, 판매가능수량 조회 로직도 옮긴다.
  - 같은 배치에서 앞 SELL 규칙의 수량을 예약분으로 차감하는 로직도 여기에 둔다.
  - **collect는 부작용이 없다.** 이력 기록은 하지 않고, 입력 수집만 한다. 프리뷰도 이 경로를 타기 때문이다. 기존 주문 → ORDERED 복구 대상은 `DcaCollected.recoveries`(ruleKey → 기존 Order)로 돌려준다. 실제 기록은 배치 쪽(`TradingCandidatePlanner`)이 `recordsRounds()` 경로에서 한다.
  - AVG_PRICE용 브로커 실잔고 조회는 삭제한다. 원장을 쓴다.
- Modify: `CycleOrderComputer.compute(...)`. DCA면 `DcaPlanInputCollector`로 `PlanContext.dca`를 채운다.
- Modify: `TradingCandidatePlanner`. `isDca()` 분기 6곳(L149·152·156·168·256·276·335)을 전략 능력 메서드로 대체한다.
  - `boolean CycleOrderStrategy.fixesPricesAtPlan()`: 기본 false, DCA true. `priceCapper.prepareForAllocation`을 건너뛸지 판단한다.
  - `boolean CycleOrderStrategy.recordsRounds()`: 기본 false, DCA true.
    - true면 `InsufficientBalanceEvent`를 발행하지 않는다.
    - true면 `OrderPlan.skips`를 이력에 기록하고, 저장된 leg는 ORDERED로 기록한다.
  - `dcaReferencePrices`는 `OrderPlan.referencePrices`에서 가져온다.
- Delete: `DcaRoundPlanner.java`, `PlannedOrder` 변환 중복
- Modify: `TradingPreviewService`. L56 DCA 예외 throw와 L82 `!s.isDca()` 필터를 제거한다. DCA 프리뷰도 `plan()` 경로를 탄다. 크립토 계좌에서 프리뷰 경로가 원래 동작하지 않는다면 그 사실만 확인하고 손대지 않는다.
- Test:
  - `DcaPlanInputCollectorTest`: 기존 `DcaRoundPlannerTest` 케이스를 이관한다.
  - `TradingCandidatePlannerTest`: DCA 케이스
  - `TradingPreviewServiceTest`: `DCA_주식_전략도_프리뷰에_포함`

**Interfaces:**
- Consumes: Task 2의 `StrategyDcaDetailPort`, `DcaExecutionPort`, `DcaExecutionRecorder`
- Produces:
  - `record PlanContext.DcaPlanInputs(List<DcaRuleInputs> dueRules)` (matching 커널)
  - `record DcaCollected(DcaPlanInputs inputs, Map<String, Order> recoveries)` (trading, collector 반환 — 커널이 trading `Order`를 모르게 분리)
  - `OrderPlan(InfinitePosition position, VrPosition vrPosition, List<PlannedOrder> orders, List<DcaRuleDecision.Skipped> skips, Map<String, BigDecimal> referencePrices)`
  - `DcaCollected DcaPlanInputCollector.collect(BatchContext ctx, LocalDate tradeDate, Set<OrderTiming> creatableTimings, List<Order> existingOrders, BigDecimal currentPrice, AccountBalance ledger)`
    - 판정할 규칙이 없으면 `dueRules`가 빈 목록이다.
    - 정확한 반환 타입은 `DcaCollected collect(...)`다. 복구 기록은 호출부가 한다.

- [ ] **Step 1: 실패 테스트 작성**
  - `TradingCandidatePlannerTest`
    - `DCA_같은_MOO에_BUY와_SELL_규칙이_모두_주문된다`
    - `DCA_조건미충족_규칙은_skip_이력_나머지는_주문`
  - `TradingPreviewServiceTest`: `DCA_주식_전략도_프리뷰에_포함`, `프리뷰는_DCA_이력을_쓰지_않는다`(`DcaExecutionPort`·`DcaExecutionRecorder`와 상호작용이 없어야 한다)
  - `HexagonalArchitectureTest`: 기존 테스트가 그대로 통과해야 한다.
- [ ] **Step 2: 실패 확인**
- [ ] **Step 3: 구현** — `isDca()`를 grep했을 때 `trading/application/service`에 남는 곳이 0이어야 한다. `Strategy.isDca()`를 쓰는 곳은 `DcaConfigService`·`DcaExecutionQueryService`(DCA 전용 API 진입 검증)만 허용한다.
- [ ] **Step 4: 통과 확인** — `./gradlew :trading-core:test --tests '*TradingCandidatePlanner*' --tests '*DcaPlanInputCollector*' --tests '*TradingPreview*' --tests '*TradingServiceTest' --tests '*BacktestGoldenTest' --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 5: Commit** — `refactor(trading): DCA 주식 경로를 CycleOrderStrategy.plan 정규 경로로 편입한다`

---

### Task 4: 주식 원장·예산 — 스냅샷 제거, 규칙 정책별 예산 축소·거절

**Files:**
- Modify: `TradingReporter`
  - `snapshotsBrokerBalance` 분기와 `liveSnapshot`을 삭제한다.
  - `postBalance = balance.applyExecutions(fills)`로 바꾼다.
  - 시드를 점유하지 않는 전략은 `withUsdDeposit(ZERO)`를 적용한다.
- Modify: `CycleOrderStrategy`
  - `snapshotsBrokerBalance()`를 삭제한다.
  - `boolean occupiesSeed()`를 추가한다. 기본 true, DCA false다.
- Modify: `PlannedOrder`. `boolean partialAllowed`를 추가한다. 기존 팩토리는 true이고, DCA REJECT 규칙 주문은 false다. 필드가 늘면 equals가 바뀌므로, allocator `mergeApproved`(`TradingOrderBudgetAllocator.java:241`)와 `TradingOrderSlots`의 equals·leg 대조가 깨지지 않는지 기존 VR 축소 회귀 테스트로 확인한다(testing.md "cap 예산 회귀").
- Modify: `DcaCycleOrderStrategy.fitBuysToBudget`. 입력 순서대로 budget을 소진한다. `partialAllowed=false` 주문은 전량이 들어가면 승인하고, 아니면 제외한다. true 주문은 `floor(남은 budget / 캡가)`로 축소하고, 0이 되면 제외한다.
- Modify: `TradingCandidatePlanner`. 승인 후보에서 원 후보 대비 빠진 DCA leg를 `INSUFFICIENT_BALANCE`(BUY)/`NO_HOLDINGS`(SELL)로 leg 단위 기록한다. 후보 전체 거절도 leg마다 기록한다.
- Test: `TradingReporterTest`, `DcaCycleOrderStrategyTest`(fitBuysToBudget), `TradingCandidatePlannerTest`

**Interfaces:**
- Consumes: Task 3 `recordsRounds()`
- Produces:
  - `PlannedOrder(..., boolean partialAllowed)`
  - `PlannedOrder.of(...)` 기존 시그니처는 `partialAllowed=true`로 둔다.
  - `PlannedOrder withPartialAllowed(boolean)`
  - `boolean CycleOrderStrategy.occupiesSeed()`

- [ ] **Step 1: 실패 테스트 작성**
  - `DcaCycleOrderStrategyTest.예산부족시_REJECT는_빠지고_PARTIAL은_축소`: budget 100, 캡가 10. REJECT BUY 8주(80)와 PARTIAL BUY 5주(50)가 순서대로 있으면 결과는 REJECT 8주 승인, PARTIAL 2주다.
  - `REJECT_BUY가_통째로_안들어가면_제외`
  - `TradingCandidatePlannerTest.빠진_규칙만_INSUFFICIENT_BALANCE_이력`(Review Focus 5)
  - `TradingReporterTest`
    - `DCA_원장은_자기주문_체결로_갱신되고_usd_deposit은_0`
    - `DCA는_브로커_잔고를_조회하지_않는다`: `liveBalancePort`와 상호작용이 없어야 한다.
- [ ] **Step 2: 실패 확인** · [ ] **Step 3: 구현** · [ ] **Step 4: 통과 확인** — `--tests '*TradingReporter*' --tests '*DcaCycleOrderStrategy*' --tests '*TradingCandidatePlanner*' --tests '*TradingOrderBudgetAllocator*'`
- [ ] **Step 5: Commit** — `feat(dca): 주식 DCA 원장을 자기 주문 체결로 갱신하고 규칙 부족 정책으로 예산을 배정한다`

---

### Task 5: 크립토 — 규칙 루프 정리와 체결 시 원장 반영

**Files:**
- Modify: `CryptoDcaRoundExecutor`
  - 대상은 전략×규칙이다. 조건은 `executionHour == 현재 시`, 실행일, `dcaExecutionPort.find(strategyId, ruleKey, today)` 없음, 같은 leg 주문 없음이다.
  - 규칙 처리 순서는 SELL 먼저, BUY 나중이다.
  - 판정은 `decideRule`로 한다. 입력은 ledger(최신 `cycle_position.toBalance()`), sellable(`min(원장, 지갑 held)`), availableCash(지갑 krw)다.
  - 지갑 로컬 차감 규칙은 기존과 같다.
- `OrderPort.markFilled`는 바꾸지 않는다. 주식 `TradingReporter.markFilledOrders`도 같은 메서드를 쓰기 때문이다.
- Create: `trading-core/.../trading/application/service/CryptoLedgerRecorder.java` (`@Transactional`)
  - `boolean recordFill(BatchContext ctx, Order order, BigDecimal volume, BigDecimal avgPrice)`
  - 순서:
    1. `strategyCyclePort.lockForUpdate(cycleId)`로 사이클을 잠근다. 수동 보정과 같은 잠금이라 둘이 직렬화된다.
    2. 주문을 다시 읽는다.
    3. `delta = volume − 기존 filledQuantity(null=0)`를 계산한다.
    4. `delta ≤ 0`이면 아무것도 하지 않고 false를 반환한다.
    5. `delta > 0`이면 체결 금액 증분 `volume×avgPrice − 기존 filledQuantity×기존 filledPrice`로 Fill(delta, 증분 금액)을 만든다. 최신 `cycle_position.toBalance().applyExecutions(fill).withUsdDeposit(ZERO)`를 계산해 `CyclePosition.tradeSnapshot(cycleId, balance, avgPrice)`로 저장한다.
    6. `orderPort.markFilled(...)`를 호출하고 true를 반환한다.
  - 이 흐름이 Review Focus 4의 멱등 보장이다. 부분 체결 뒤 전량 체결이 와도 증분만 반영된다.
  - `closing_price`에는 체결 평균가를 기록한다.
- Modify: `CryptoOrderSynchronizer.settle`. `markFilled`와 이벤트 발행을 `CryptoLedgerRecorder.recordFill`로 바꾸고, 이벤트는 true일 때만 발행한다.
- Modify: `CryptoDcaRunner`. `findActiveByStrategyIds`의 반환 타입이 `DcaStrategyConfig`가 된 데 맞춘다.
- Test: `CryptoDcaRoundExecutorTest`, `CryptoOrderSynchronizerTest`, `CryptoLedgerRecorderIT`(실 DB, `@Tag("integration")`)

**Interfaces:**
- Consumes: Task 1 `decideRule`, Task 2 ports, Plan 1 `AccountBalance(BigDecimal …)`
- Produces: `CryptoLedgerRecorder.recordFill(...)`

- [ ] **Step 1: 실패 테스트 작성**
  - `CryptoDcaRoundExecutorTest`
    - `같은틱_SELL_BUY_동시발동은_SELL먼저_각자_이력`(Review Focus 2)
    - `09시_매수체결후_10시_AVG_PRICE_매도는_갱신된_평단으로_판정`(spec 시나리오 3. 원장 stub으로 09시 이후 상태를 준다)
  - `CryptoLedgerRecorderIT`: `같은_주문_두번_settle해도_원장은_1회_반영`, `부분체결_후_전량체결은_증분만_반영`(Review Focus 4)
- [ ] **Step 2: 실패 확인** · [ ] **Step 3: 구현** · [ ] **Step 4: 통과 확인**
  - `./gradlew :trading-core:test --tests '*Crypto*' 2>&1 | grep -E "FAILED|BUILD"`
  - `./gradlew :trading-core:integration --tests '*CryptoLedgerRecorderIT' 2>&1 | grep -E "FAILED|BUILD"`
- [ ] **Step 5: Commit** — `feat(dca): 크립토 DCA를 규칙 단위로 실행하고 체결 확정 시 원장에 반영한다`

---

### Task 6: DCA 수동 체결 보정 개방

**Files:**
- Modify: `ManualTradeCorrectionService`. L55 DCA 가드를 제거한다. SELL 보유 검증은 `fill.quantity().compareTo(balance.holdings()) > 0`으로 한다. DCA는 `occupiesSeed()=false`이므로 보정 후 `usd_deposit`도 0이다.
- Modify: `ManualTradeCorrectionCommand.Fill.quantity`를 `@Positive BigDecimal`로 바꾼다. 주식 계좌에서 소수 수량이면 IAE(400)다.
  - `Order.filledManual(BigDecimal …)`
  - `Execution.ofManualFill` 경로: 원장에는 `AccountBalance.Fill`을 직접 만든다. 브로커 `Execution` int는 유지한다.
- Modify: 요청 DTO, `:shared` 보정 요청 계약의 quantity를 BigDecimal로, root 관리자 보정 경로가 있으면 함께 바꾼다.
- Test: `ManualTradeCorrectionServiceTest`
  - `DCA_보정은_원장에_반영되고_사이클은_유지`
  - `크립토_소수_보정`
  - `주식_소수_보정은_400`

**Interfaces:** Consumes Plan 1·Task 4 `occupiesSeed()`. Produces `ManualTradeCorrectionCommand.Fill(LocalDate, OrderDirection, BigDecimal quantity, BigDecimal price, String externalOrderId, String memo)`

- [ ] **Step 1~4:** 실패 테스트 → 실패 확인 → 구현 → `--tests '*ManualTradeCorrection*'` 통과(root 경로를 바꿨으면 `:test --tests '*TradeCorrection*'`도)
- [ ] **Step 5: Commit** — `feat(dca): DCA 전략에도 수동 체결 보정을 연다`

---

### Task 7: 통계·포트폴리오에 DCA 포함 — 원금 = 초기 투입 원가 + 누적 순투입액

**Files:**
- Modify: `TradingStatsService`
  - L171 `!s.isDca()`를 제거한다.
  - `buildPoints`의 원금 계산을 바꾼다. `occupiesSeed()=false` 전략이면 `startAmount + Σ(d 이하 자기 주문 BUY 체결금액) − Σ(SELL 체결금액)`이다.
  - `unrealizedByCycle`과 수익률(L189·217·277)도 같은 원금을 쓴다.
- Modify: `InvestmentPointsQueryService`(L56 필터 제거)와 `InvestmentIndexCalculator`의 원금 입력에 같은 규칙을 적용한다.
- Create(필요 시): `OrderPort.findFilledNetInvestedByCycleIds(Collection<UUID>) -> Map<UUID, NavigableMap<LocalDate, BigDecimal>>`(일자별 누적)
- 통화 확인: 통계 합산은 통화 구분이 없다. 계좌 단위 통계는 계좌가 단일 통화라 문제가 없다. 사용자 전체 합산 경로에서 KRW(크립토)와 USD가 섞이는지 확인한다. 섞이면 그 합산 경로에서만 `Market.CRYPTO_KRW` 계좌를 제외하고, 제외 사실을 코드 주석과 완료 보고에 남긴다(spec §5.7 빌더 판단).
- Test: `TradingStatsServiceTest`
  - `DCA_원금은_누적순투입액`: 1일차 BUY 100, 2일차 BUY 100, 3일차 SELL 50이면 원금 시계열은 100, 200, 150이다. 평가액이 원금과 같으면 수익률 0이어야 한다.
  - `DCA_초기보유_원가가_시작원금`
  - `InvestmentPointsQueryServiceTest`의 DCA 포함 케이스

**Interfaces:** Consumes Task 4 `occupiesSeed()`, Task 2 `start_amount` 규칙. Produces 통계 응답 스키마 변경 없음.

- [ ] **Step 1~4:** 실패 테스트 → 실패 확인 → 구현 → `--tests '*TradingStats*' --tests '*InvestmentPoints*' --tests '*InvestmentIndex*'` 통과
- [ ] **Step 5: Commit** — `feat(stats): DCA 전략을 통계·포트폴리오에 포함하고 원금을 누적 순투입액으로 계산한다`

---

### Task 8: 문서·아키텍처 맵·전체 검증·kista-ui 계약 전달

**Files:**
- Modify: `src/test/resources/architecture/flows.yml`. 조사 기준 줄은 L40·55·138·143-152·327-335·510-513·817·1117·1121·1620-1656이다.
  - `dca-round` 단계를 `DcaPlanInputCollector`·`plan()` 기반으로 바꾼다.
  - 크립토 원장 반영(`CryptoLedgerRecorder#recordFill`)을 추가한다.
  - 통계 DCA 제외와 수동 보정 거부 문구를 제거한다.
  - 등록 검증에서 계좌+종목 1개 규칙을 적용한다.
- Modify:
  - `docs/agents/modules/tradingcore/trading.md`, `matching.md`
  - `README.md`(DCA 설명이 있으면)
  - `docs/superpowers/specs/2026-10-13-dca-strategy-design.md` 상단에 "§3.4·§5.4는 2026-10-21 spec으로 대체" 한 줄을 넣는다.
- [ ] **Step 1: 문서 갱신**
- [ ] **Step 2: 전체 스위트 1회** — `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`와 `./gradlew integration 2>&1 | grep -E "FAILED|BUILD"` 실행 시 Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `docs(dca): 다중 규칙·DB 원장 흐름을 모듈 문서와 아키텍처 맵에 반영한다`
- [ ] **Step 4: kista-ui-fc 세션에 확정 계약 전달**
  - `SendMessage`로 `kista-ui-fc`에 보낸다.
  - 내용: openapi diff 요약(DCA rules 구조, `initialHoldings` number, holdings 계열 `integer`→`number`, `DcaExecutionItem.ruleKey/direction`, 메타 enum 2개, `DcaSkipReason.INSUFFICIENT_HOLDINGS`)
  - 배포 짝: kista-api와 kista-ui를 함께 배포해야 한다.

## 배포 메모

- 배포 순서: Plan 1을 root와 trading-core 양쪽에 배포한 뒤 Plan 2를 배포한다. Plan 2 전까지는 소수 holdings가 생기지 않는다.
- V12는 운영 DCA 데이터를 삭제한다. 배포 전에 읽기 전용으로 `SELECT count(*) FROM trading.strategy WHERE type='DCA'`를 실행해 대상 건수를 확인하고 사용자에게 보고한다.
- 배포 직전 EPR 정리: `DcaRoundSkippedEvent`의 payload가 바뀌므로, 미완료 `trading.event_publication` 행을 `docker-infra.md` "배포 직전 EPR 정리 런북"대로 확인한다.
- kista-api와 kista-ui는 함께 배포한다. 구 UI는 DCA 등록·수정이 400이 난다.
