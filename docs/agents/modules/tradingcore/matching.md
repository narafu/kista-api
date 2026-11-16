## com.kista.tradingcore.matching (`:trading-core`)

Spring Modulith 모듈(CLOSED) — 주문생성 커널(순수 계산). `domain/model` + `domain/strategy`가 `"kernel"` NamedInterface로 병합 공개된다. outbound 엣지는 `sharedkernel`뿐이며 `com.kista..` 중 `matching`·`sharedkernel` 밖 전부를 금지한다 (가드: `HexagonalArchitectureTest.matching_must_not_depend_on_other_modules`). domain은 Spring 비의존 — 빈 배선은 trading `CycleStrategyBeanConfig`, stats `BacktestEngine`은 순수 도메인 원칙으로 직접 `new`한다. trading의 등록 정책 리졸버는 `trading/domain/creation`으로 이름을 분리했다(같은 하위 패키지명을 다른 의미로 재사용 금지).

- PRIVACY 기준 매매표는 커널 소유 `PrivacyPlan`으로 받는다(privacy → matching 단방향). 변환은 데이터 소유자 `PrivacyTradeBase.toPlan()`이 맡는다.
- `AccountBalance`의 `holdings`/`avgPrice`/`deposit`은 BigDecimal(null 금지). `wholeShares()`는 `intValueExact()`라 소수 수량이면 ArithmeticException — 주식 공식(INFINITE/VR/PRIVACY/리버스/백테스트)은 holdings를 `wholeShares()`로만 읽는다. 브로커 벤더 타입(SellableQuantity/Execution)과 `PrivacyPlan`은 int. `Execution→Fill` 변환은 호출부(`TradingReporter`·`AdminTradeCorrectionService`·`BacktestEngine`)가 담당한다.
- `PlanContext`는 `ticker`·`accountId`(로그 문맥, backtest는 null)만 갖는다 — matching↔trading 순환 방지. 현재가+전일종가는 `broker.PriceSnapshot`이고 커널은 `BigDecimal` 스칼라만 받는다.
- 매매 공식 SSOT는 `trading-formulas.md`다. **공식을 건드리면 작업 전 반드시 Read한다.**

### capability와 보정 훅
- capability SSOT = `StrategyType.capability()`(sharedkernel `StrategyCapability` 컴파일 타임 상수). `CycleOrderStrategy.requiresPrivacyBase()`/`supportsReverseMode()`/`availableDivisionCounts()`는 이를 위임하는 기본 메서드이고 구현체가 재정의하지 않는다. `GET /api/meta`도 같은 상수를 직접 읽어 UI가 `isInfinite` 휴리스틱 없이 소비한다. 값을 바꾸려면 `StrategyType.capability()` 한 곳만 고친다. 신규 전략 타입 = `StrategyType` case + `CycleOrderStrategy` 구현체 1개.
- BUY 가격 사후 보정(`capBuyOrders()`/`capsIndividualOrders()`/`needsCapCheck()`): INFINITE/VR은 사다리 전체 재구성(`capsIndividualOrders()=false`), PRIVACY는 개별 주문 가격만 치환(`true`). VR도 생성 시점 cap 없이 접수 전 `BuyOrderPriceCapper`가 `capBuyOrders()`로 보정한다. 공용 BUY 치환은 `PriceCapPolicy.replaceBuysPreservingOrder`.
- `fitBuysToBudget(buyOrders, budget)`: `StrategyLedgerBudget.fit`(유일한 호출자)이 원장 잔여 부족 시 부르는 BUY 축소 훅. 기본은 축소 없음(전체 거절)이고 DCA는 override하지 않는다. INFINITE는 `InfiniteStrategy.trimCorrectionsToBudget`(뒤쪽 correction leg부터 제거, base 불변), VR은 싼 단(꼬리)부터 rung 단위로 자른다(`buildBuyLadder`의 pool 예산 break와 같은 방향이라 공식 불변). 입력 BUY의 순서를 유지한 축소본을 반환하며 호출자는 leg 이름을 모른다.
- `canSkipOrderComputation()`은 기본 false, INFINITE만 complete concrete leg 조합을 보수적으로 판단한다.
- 최소시드 미리보기 `GET /api/accounts/{id}/strategy-seed-preview`(`StrategyHistoryQueryService.strategySeedPreview`)는 브로커 HTTP 호출을 포함해 `@Transactional(NOT_SUPPORTED)`다(`register()`와 동일). PRIVACY + 기준 매매표 없는 날은 `skipReason="NO_PRIVACY_BASE"`(basePrice/minSeed=null).

### DCA 전략 (`DcaCycleOrderStrategy`)
- 공통 `plan`(규칙마다 `decideRule` → `Planned`/`Skipped`). `needsCapCheck=false`.
- BUY 부족 정책은 `decideRule`이 `DcaRuleInputs.availableCash`(원장 예수금 잔여)로 직접 판정한다: 가용 수량 = `floor(가용 / 캡가)`. REJECT는 부족하면 `INSUFFICIENT_BALANCE` skip, PARTIAL은 가용 수량으로 축소(1주도 못 사면 `INSUFFICIENT_BALANCE`). `plan()`이 앞 규칙의 BUY(수량 × 캡가)를 뒤 규칙 가용에서 빼 규칙 합계가 가용을 넘지 않게 한다(SELL의 `plannedSell`과 같은 방식).
- `availableCash`·SELL 상한 `sellableAfterReserved`가 null이면 "원장 조회 실패" `DATA_UNAVAILABLE` skip이다(상한 없음 값은 없다). SELL 상한은 수집기가 원장 보유 − 기존 SELL − 취소된 접수 SELL 체결분으로 계산하고 브로커는 조회하지 않는다.
- 판정 순서(`decideRule`): ① 데이터(종가·원장·SELL 상한·모든 지표 계산 가능 여부) → ② 조건 AND(입력 순서, 처음 실패한 조건이 `CONDITION_NOT_MET`와 뒤집힌 연산자 detail을 남긴다) → ③ `NO_HOLDINGS`/`INSUFFICIENT_HOLDINGS`(SELL, 원장 보유 기준) → ④ `BELOW_ONE_SHARE`. AVG_PRICE는 보유 0주일 때 BUY는 통과하고 SELL은 통과 후 `NO_HOLDINGS`로 막힌다.
- FEAR_GREED는 CNN 값을 쓴다.

## 테스트 패턴
- `currentRound`(double) 단언: 정확한 정수는 `isEqualTo(5.0)`, 소수점은 `isCloseTo(1.33, within(0.01))`
