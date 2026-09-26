# trading 실행 경로 중복 제거 설계

## 배경

사용자가 "trading 모듈이 기능 대비 로직이 과한 것 같다"며 구조적 리팩토링을 요청했다. Sonnet 서브에이전트 3회 조사(오케스트레이션 레이어 수, CycleOrderStrategy capability 패턴, domain model 크기, persistence adapter 보일러플레이트, VR 파라미터)는 전부 "유지 근거 있음"으로 수렴했다. Opus로 독립 재분석한 결과, 문제는 클래스/레이어 수가 아니라 **같은 계산이 여러 실행 경로(단건 미리보기/배치 미리보기/야간 배치/수동 실행)에 반복 구현**되어 있다는 데 있었다.

이 설계는 그 중 실행 경로 통합 3건을 다룬다. 나머지 발견(capability 플래그 중복, `TradingExecutionFacade` 정리, `StrategyInfiniteDetail`/`CyclePositionInfiniteDetail` 테이블 흡수)은 이번 스코프에서 제외한다 — 앞 둘은 효과가 낮은 기계적 정리라 별도 트랙(ponytail-audit)에서 처리하고, 테이블 흡수는 스키마 마이그레이션이 필요해 사용자가 이번 라운드에서 보류했다.

## 스코프

1. 미리보기 단건/배치 경로 통합
2. 예산 배정기(`TradingOrderBudgetAllocator`) 단일계좌 전용화 + 수동실행 경로 통합
3. `PriceCapMode` 삼항 분기를 `CycleOrderStrategy` capability 다형성으로 편입

**스코프 제외**: capability 플래그 중복 정리, `TradingExecutionFacade` 얇은 위임 정리, `StrategyInfiniteDetail`/`CyclePositionInfiniteDetail` 테이블 흡수(스키마 변경 필요, 별도 설계로 분리).

## 1. 미리보기 단건/배치 경로 통합

### 현재 구조

- `TradingPreviewService.preview(strategyId, requesterId)`: 대상 전략 1건만 조회해 `buildPreview(...)`를 precomputed 인자 전부 `null`로 호출.
- `TradingPreviewService.previewBatch(accountId, requesterId)`: 계좌 내 전략 전체의 cycle·당일주문·전일종가·매매계획을 1회씩 배치 조회해 `BatchContext`로 묶고, 전략마다 `buildPreview(...)`를 precomputed 인자와 함께 호출.
- `buildPreview`(`TradingPreviewService.java:146-211`)는 4개 정도의 `x != null ? x : 직접 조회` 분기를 가진다.
- `TradingBuyCompetitionSimulator.simulate(...)`에 2-인자(단건, `context=null`)/7-인자(배치, `context`) 오버로드가 공존(`TradingBuyCompetitionSimulator.java:58-66`). `context=null`일 때는 매 호출마다 `strategyPort.findByAccountId`로 계좌 내 전략을 다시 조회하고 경쟁 전략마다 `planBuilder.build`를 그때그때 계산한다.
- `StrategyOrderPlanBuilder.build(...)`도 같은 이유로 6-인자/5-인자 오버로드가 공존(`StrategyOrderPlanBuilder.java:44-51`).

단건 미리보기도 BUY 주문이 있으면 경쟁 시뮬레이션 때문에 계좌 내 다른 활성 전략을 전부 다시 계산한다 — 이미 배치와 동급의 계산량인데 코드 경로만 갈라져 있다.

### 변경 후 구조

- `preview(strategyId, requesterId)`를 다음으로 교체:
  ```
  Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
  accountPort.requireOwnedAccount(strategy.accountId(), requesterId); // 소유권 검증은 그대로 유지
  Map<UUID, NextOrdersPreview> batch = previewBatch(strategy.accountId(), requesterId);
  return Optional.ofNullable(batch.get(strategyId))
          .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategyId));
  ```
- `buildPreview`의 precomputed 인자 4개(`precomputedTodayOrders`/`precomputedPlanResult`/`context`/`precomputedTotalAccountPlannedBuy`)를 필수 인자로 바꾸고 null 분기 제거 — 호출부는 항상 `previewBatch` 경로 하나뿐이므로 단건 fallback 분기가 죽은 코드가 된다.
- `TradingBuyCompetitionSimulator.simulate`의 2-인자 오버로드, `context == null` 분기 제거 — `BatchContext`를 필수 인자로.
- `StrategyOrderPlanBuilder.build`의 5-인자 오버로드 제거 — `prevCloseCache`를 필수 인자로(단건 호출도 이제 배치를 경유하므로 항상 값이 있음).

### 응답 계약

`NextOrdersPreview`/`BuyCompetitionPreview` 응답 구조는 변경하지 않는다. `kista-ui`가 두 엔드포인트(`GET /api/trading-cycles/{id}/preview`, `GET /api/accounts/{id}/preview`)를 그대로 호출하며, 서버 내부 구현만 바뀐다 — UI 변경 불필요.

### 트레이드오프 (명시적으로 받아들이는 동작 변화)

- **기존 단건 preview**: 경쟁 전략 중 오늘 이미 주문을 낸 전략(`alreadyOrdered`)은 계산을 건너뛴다.
- **통합 후**: `previewBatch`는 계좌 내 활성 전략 전부의 계획을 조건 없이 항상 계산한다(이미 주문 낸 전략도 포함) — 이는 배치 자체가 "계좌 내 모든 전략의 오늘자 미리보기"를 만드는 것이 목적이라 원래도 그렇게 동작했다.
- 계좌당 활성 전략 수는 소규모라는 전제(`TradingPreviewService.java:61` 주석)가 기존에도 이 배치 비용을 정당화하고 있었으므로, 단건 호출이 이 비용을 매번 지불하게 되는 것은 실사용상 유의미한 회귀로 보이지 않는다. 단, 전략 수가 많은 계좌에서 단건 조회(예: 상세 화면 진입)가 상대적으로 느려질 수 있다는 점은 구현 중 실측으로 재확인한다.

### 테스트

- `TradingPreviewServiceTest`(존재 시) 중 단건 preview 전용 케이스가 batch 위임 이후에도 동일 응답을 내는지 확인.
- 경쟁 전략이 이미 오늘 주문을 낸 케이스 — 기존에는 스킵됐던 계산이 이제 수행되지만 최종 응답(competition 필드)에 영향이 없는지 회귀 테스트로 확인(비용만 늘고 결과는 동일해야 함 — `alreadyOrdered`인 경쟁자는 애초에 `ranked`에 포함되지 않으므로).

## 2+4. 예산 배정기 단일계좌 전용화 + 수동실행 경로 통합

### 현재 구조

- `TradingOrderBudgetAllocator`는 다계좌 병렬 선조회 구조를 갖는다: `fetchLiveQuotes(List<List<Candidate>>)` → 계좌별 `TradingParallelRunner` 태스크로 `fetchQuoteInline` 실행 → `LiveQuotes`(계좌별 `AccountQuote` 맵, 실패는 `AccountQuote.failure`로 보존) → `allocate(candidates, tradeDate, quote)`가 `rethrowIfFailed(quote)`로 나중에 예외를 다시 던짐.
- 하지만 실제 호출부(`TradingCandidatePlanner.java:214-218`)는 이미 계좌별로 묶은 `candidates`만 `allocate`에 넘긴다 — `allocate` 내부의 `allocateBuysByAccount`가 다시 `candidatesByAccount`로 재그룹(`TradingOrderBudgetAllocator.java:203-211`)하고 `accountTickerComparator`가 accountId까지 비교하는 것은 전부 "여러 계좌가 섞여 들어올 수 있다"는, 실제로는 성립하지 않는 가정에 대한 방어 코드다.
- `ManualTradingService.execute()`(`ManualTradingService.java:50-108`)는 잔고 로드→전일종가→privacyBase→compute를 인라인으로 재구현(73-86번째 줄이 `StrategyOrderPlanBuilder.build`와 동일 흐름, "PrivacyTradePort에는 이 조합 전용 헬퍼가 없어 동일 로직을 인라인"이라는 주석이 두 파일에 동일하게 존재) 하고, BUY 예수금 검증(`hasSufficientDepositFor`, 91-95번째 줄)과 SELL 검증(`checkSellableOrThrow`, 142-154번째 줄)을 배정기 로직과 별개로 재구현한다.
- **버그**: 야간 배치(`TradingCandidatePlanner.java:148,218`)는 `priceCapper.prepareForAllocation`으로 BUY 가격을 캡 적용한 **후** `budgetAllocator.allocate`로 예산 검증한다. `ManualTradingService.execute()`는 캡을 적용하지 않은 원본 가격으로 예수금을 검증한다(가격 캡은 이후 `placeAtOpenOrdersIfMarketOpen`에서 개장 후에만 적용됨, 개장 전 접수 시점엔 아예 미적용). 캡은 초과 가격을 낮추는 보정이라 보통 필요 금액이 줄어드는 방향이지만, 두 경로가 서로 다른 기준으로 같은 것을 검증하고 있다는 사실 자체가 불일치다.

### 변경 후 구조

**배정기**
- `fetchLiveQuotes`/`LiveQuotes`/`AccountQuote.failure`/`rethrowIfFailed`를 삭제한다.
- `TradingCandidatePlanner`가 계좌별 병렬 처리를 할 때, "조회+배정"을 `TradingBatchGuard.runSafely` 안에서 한 작업으로 묶는다:
  ```
  parallelRunner.runAll(accountsWithCandidates.map(entry ->
      new Task(entry.accountId(), () -> batchGuard.runSafely(entry.accountId(),
          () -> Optional.of(allocator.allocate(entry.candidates(), tradeDate))))));
  ```
  (`allocate(candidates, tradeDate)` 2-인자 버전이 내부에서 `fetchQuoteInline` 동기 호출 후 배정까지 수행 — 기존에도 존재하던 메서드가 정식 진입점이 된다.) 실패 격리·계좌당 알림 1회 계약은 `runSafely`가 그대로 담당하므로 `AccountQuote.failure`로 예외를 값으로 들고 다니다 나중에 rethrow하는 장치가 불필요해진다.
- `allocateBuysByAccount`의 `candidatesByAccount` 재그룹 로직 제거 — 입력 자체가 이미 단일 계좌이므로 바로 `sorted = candidates.stream().sorted(buyPriorityComparator())`로 시작.
- `accountTickerComparator`를 `Comparator.comparing(AccountTicker::ticker)`로 축소(또는 `AccountTicker` 레코드에서 `accountId` 필드 제거하고 `StrategyTicker` 단독 키로 교체).

**수동실행**
- `ManualTradingService.execute()`의 73-86번째 줄(잔고 로드~compute)을 `planBuilder.build(strategy, account, currentCycle, today, account.nickname())` 단일 호출로 교체. skip 결과(`PlanResult.isSkip()`)는 기존과 동일하게 빈 리스트 반환으로 매핑.
- BUY/SELL 검증 91-98번째, 138-154번째 줄을 `priceCapper.prepareForAllocation(...)`로 캡 적용 후 `allocator.allocate(List.of(candidate), today)` 호출로 교체. `Allocation.rejectedBuy`가 비어있지 않으면 `ManualTradingException("예수금이 부족합니다")`, `rejectedSell`이 비어있지 않으면 `ManualTradingException("보유 수량이 부족합니다")`.
- 이 변경으로 수동실행도 배치와 동일하게 **캡 적용 후 금액**으로 검증하도록 통일된다 — 위에서 발견한 불일치가 함께 해소된다. 이는 의도적 동작 변경이므로 아래 "동작 변경" 절에 명시한다.

### 동작 변경 (명시적으로 결정해야 할 것)

- 수동실행의 예수금/보유수량 검증 기준이 "가격 캡 적용 전"에서 "적용 후"로 바뀐다. 캡은 초과 가격을 낮추는 보정이므로 일반적으로 필요 금액이 줄어드는 방향 — 즉 기존에 예수금 부족으로 거부되던 경계 케이스가 캡 적용 후에는 통과할 수 있다. 이는 버그 수정으로 간주하고 진행한다(사용자 승인 완료 — 이번 스코프에 포함).

### 테스트

- `TradingOrderBudgetAllocatorTest`: 다계좌 입력을 가정한 기존 테스트가 있다면 단일계좌 전용으로 스코프가 좁혀지는 부분을 확인 — 다계좌 호출 자체가 애초에 실사용에 없었음을 재확인(운영 코드 호출부 grep으로 재검증).
- `ManualTradingServiceTest`: 캡 적용 전/후 금액이 갈리는 경계값 케이스(캡 적용 전엔 부족, 적용 후엔 충분)를 신규로 추가해 동작 변경을 고정.
- `TradingCandidatePlannerTest`: 계좌별 알림 1회 계약(현재 `runSafely` 호출 횟수 검증)이 조회+배정 통합 이후에도 유지되는지 확인.

## 3. PriceCapMode 삼항 분기 → CycleOrderStrategy capability 편입

### 현재 구조

- `CycleOrderStrategy.PriceCapMode` enum(`NONE`/`INFINITE_POSITION`/`PRIVACY_SIMPLE`/`VR_POSITION`, `CycleOrderStrategy.java:56-57`)과 `priceCapMode()` capability 메서드는 이미 다형성으로 조회 가능하다.
- 하지만 실제 캡 적용 로직은 다형성을 타지 않고 3곳에서 enum switch로 분기한다:
  - `BuyOrderPriceCapper.prepareForAllocation`(`BuyOrderPriceCapper.java:48-75`)
  - `BuyOrderPriceCapper.capIfNeeded`(`:83-100`)
  - `TradingOrderExecutor.applyCap`(`TradingOrderExecutor.java:69-75`)
- 구현체 3개(Infinite/Privacy/Vr)가 각자 `priceCapMode()`를 override하므로 `mode == null`, `mode == NONE` 분기는 도달 불가능한 방어 코드다.
- `BuyOrderPriceCapper`가 캡 계산을 위해 커널 빈 `InfiniteStrategy`/`VrStrategy`를 직접 주입받는다(`:42-43`) — capability 다형성 계층을 우회하는 지점이다.

### 변경 후 구조

`CycleOrderStrategy`에 캡 적용 자체를 캡슐화하는 default 메서드를 추가한다:

```java
// 캡 초과 BUY만 재산정된 값으로 치환, 캡 불필요/미적용 대상이면 원본 그대로 반환
default List<PlannedOrder> capBuys(List<PlannedOrder> orders, BigDecimal cap,
                                    InfinitePosition position, VrPosition vrPosition,
                                    StrategyTicker ticker, LocalDate tradeDate) {
    return orders; // 기본: 캡 미적용 — 신규 전략 타입이 캡 로직을 아직 구현하지 않았을 때의 안전한 기본값
}
```

- `InfiniteCycleOrderStrategy`: `position == null`이면 원본 반환, 아니면 `infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap)` 후 `PriceCapPolicy.replaceBuysPreservingOrder`.
- `VrCycleOrderStrategy`: `vrPosition == null`이거나 `isVrBootstrapShaped(buyOrders)`면 원본 반환, 아니면 `vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap)` 후 치환. `isVrBootstrapShaped`는 이 클래스로 이동.
- `PrivacyCycleOrderStrategy`: 캡 초과 BUY만 `withPrice(cap)`로 단순 치환(현재 `applyPrivacyCap` 로직 그대로 이 클래스로 이동).
- `BuyOrderPriceCapper.prepareForAllocation`/`capIfNeeded`, `TradingOrderExecutor.applyCap`의 mode별 if-else 3곳을 `cycleOrderStrategies.of(strategy.type()).capBuys(...)` 단일 호출로 교체.
- `PriceCapMode` enum과 `priceCapMode()` capability 메서드 삭제.
- `BuyOrderPriceCapper`의 `InfiniteStrategy`/`VrStrategy` 직접 주입 제거 — capability 위임만으로 충분해짐.

`capIfNeeded`(DB 반영 경로)의 PRIVACY 초과분만 취소 vs INFINITE/VR 전체 취소라는 기존 동작 차이는 그대로 보존한다 — `applyCapIfNeeded`/`applyPrivacyCap`의 분기 자체를 각 구현체 내부로 옮기는 것이므로 외부에서 관찰되는 동작은 동일하다.

### 테스트

- 기존 `BuyOrderPriceCapperTest`/`TradingOrderExecutorTest`(존재 시)는 그대로 통과해야 한다 — 순수 리팩토링(관찰 가능한 동작 변화 없음)이므로 assertion 변경 불필요, 호출 경로만 capability 경유로 바뀜을 확인.
- `CycleOrderStrategy` 신규 default 메서드에 대해 커널 단위 테스트(`InfiniteCycleOrderStrategyTest` 등, 존재 시 확장) 추가 — mode 문자열이 아닌 전략 타입별 캡 계산 결과를 직접 검증.

## 리스크 및 순서

1번(미리보기) → 2+4번(배정기+수동실행) → 3번(가격캡) 순서로 진행한다. 1~3번 모두 매매 실행 경로에 직접 관여하므로:
- 구현 전 `docs/agents/workflow.md`(스케쥴러 실행 흐름 SSOT) 재확인 필수.
- 각 항목 구현 후 커밋 전 리뷰어 검수 필수(전역 CLAUDE.md 규칙).
- 2+4번은 실제 동작 변경(캡 적용 후 검증)을 포함하므로 회귀 테스트 없이 병합 금지.

## 제외 사항 재확인

- capability 플래그 중복(`tracksReverseMode`/`supportsReverseMode`, `requiresRolloverCheck`/`!endsCycleOnLiquidation`) 정리와 `TradingExecutionFacade` 미사용 시그니처 정리는 ponytail-audit 트랙에서 별도 처리.
- `StrategyInfiniteDetail`/`CyclePositionInfiniteDetail` 테이블 흡수는 스키마 마이그레이션이 필요해 이번 스코프에서 제외 — 필요 시 별도 설계 문서로 분리해 재논의한다.
