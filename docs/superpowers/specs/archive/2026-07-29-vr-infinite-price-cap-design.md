# VR/INFINITE 가격 기준 및 BUY cap 보정 설계

## 배경

VR 미리보기에서 첫 사이클 주문이 나오지 않는 원인은 `StrategyOrderPlanBuilder`가 `CycleOrderComputer.compute(..., currentPrice)`에 `null`을 넘기고, `VrStrategy.buildBootstrapOrders()`가 현재가 없으면 빈 주문을 반환하기 때문이다.

현재 INFINITE는 전일종가로 주문 계획을 만들고, BUY 주문가가 현재가 기반 cap을 넘으면 접수 전 `BuyOrderPriceCapper`에서 재산정한다. 이 방식은 전략별 주문 공식과 공통 BUY 보호 정책이 분리되어 있다. VR도 같은 구조로 맞추면 미리보기와 실제 주문 생성의 일관성을 높일 수 있다.

## 목표

- 미리보기와 계획 생성은 전략별 기준가격으로 결정한다.
- 실제 BUY 접수 전에는 현재가 기반 cap을 공통 정책으로 적용한다.
- 동일 ticker 현재가를 전략마다 반복 조회하지 않고, 실행 구간별로 일괄 조회한다.
- INFINITE, PRIVACY, VR의 전략별 재산정 공식은 각 전략 로직에 남긴다.
- VR 첫 사이클 BUY 미리보기는 전일종가 기준으로 생성되게 한다.

## 비목표

- SELL 주문 cap 정책은 추가하지 않는다.
- VR 공식 자체, pool 계산, rollover 기준은 변경하지 않는다.
- 브로커 API의 가격 조회 구현체는 바꾸지 않는다.
- 주문 cap 배수 자체는 이번 설계에서 변경하지 않는다. 실제 배수는 `PriceCapPolicy`를 단일 기준으로 유지한다.

## 현재 구조

- `TradingService.loadPriceContext()`가 실행 시작 시 필요한 ticker 가격 스냅샷을 일괄 조회한다.
- `CycleOrderComputer`는 전략별 입력을 조립하고, VR에는 현재 `currentPrice`를 넘길 수 있는 구조가 있다.
- `InfiniteCycleOrderStrategy.requiresPrevClose()`는 `true`라 INFINITE 미리보기와 계획 생성에서 전일종가를 요구한다.
- `VrCycleOrderStrategy.requiresPrevClose()`는 현재 `false`이고, VR bootstrap은 현재가가 없으면 주문을 만들지 않는다.
- `BuyOrderPriceCapper`는 INFINITE/PRIVACY의 BUY cap 보정을 담당한다.
- VR은 현재 `VrStrategy.buildBuyOrders()` 내부에서 현재가 cap을 직접 적용하고, `PriceCapMode.NONE`이라 접수 전 공통 보정 경로를 타지 않는다.
- `TradingOrderExecutor.placeGiven()`은 받은 주문을 바로 접수하므로 AT_OPEN 선접수 주문에는 BUY cap 보정이 적용되지 않는다.

## 설계

### 1. 가격 역할 분리

가격 입력은 두 종류로 분리한다.

- `referencePrice`: BUY 주문 계획과 미리보기 산출 기준. INFINITE와 VR은 전일종가를 사용한다.
- `placementPrice`: 실제 증권사 접수 직전 BUY cap 판단 기준. 현재가를 사용한다.

VR에서는 기존 `currentPrice` 입력 의미를 `referencePrice`와 `placementPrice`로 분리해야 한다. 특히 bootstrap BUY 가격은 `referencePrice * 1.10`으로 만들고, 접수 직전 `PriceCapPolicy.capFor(placementPrice)`를 초과하면 cap 보정한다.

### 2. 접수 직전 현재가 일괄 재조회

`TradingOrderExecutor`가 전략별로 현재가를 직접 조회하지 않는다. 대신 `TradingService`가 주문 접수 직전에 접수 대상 `states`의 ticker를 모아 한 번 더 가격 스냅샷을 조회한다.

마감 경로:

```text
loadPriceContext(contexts, today)
→ planAll(... startPriceSnapshots ...)
→ waitUntilOrderTime()
→ reloadPlacementPrices(states)
→ placeAll(states, placementPriceSnapshots, today)
```

개장 경로:

```text
loadPriceContext(contexts, tradeDate)
→ waitUntilMarketOpen()
→ plan candidates
→ saveAllocatedOrders(...)
→ reloadPlacementPrices(placeableContexts)
→ placeAtOpenPlannedOrders(... placementPrice ...)
```

이렇게 하면 `TQQQ` 전략이 여러 개 있어도 접수 직전 현재가 조회는 ticker당 1회로 유지된다.

### 3. 공통 BUY cap 보정 경로 확장

`CycleOrderStrategy.PriceCapMode`를 전략별 재산정 방식을 나타내는 정책으로 유지하되, VR용 mode를 추가한다.

- `INFINITE_POSITION`: `InfiniteStrategy.buildCappedBuyOrders()`로 수량과 correction BUY 재산정
- `PRIVACY_SIMPLE`: cap 초과 BUY 가격만 단순 치환
- `VR_POSITION`: VR pool, 사다리, bootstrap 예산을 반영해 BUY 재산정
- `NONE`: cap 미적용

`BuyOrderPriceCapper`는 cap 판단, 기존 PLANNED BUY 취소, 보정 주문 저장 흐름을 공통으로 담당한다. 전략별 "어떻게 다시 만들지"는 각 전략 구현으로 위임한다.

### 4. VR 주문 생성 변경

VR BUY 계획 생성은 전일종가를 기준으로 한다.

- `VrCycleOrderStrategy.requiresPrevClose()`를 `true`로 변경한다.
- `CycleOrderComputer`는 VR 입력에 전일종가 기반 `referencePrice`를 전달한다.
- `VrStrategy.buildBootstrapOrders()`는 BUY bootstrap에 `referencePrice * 1.10`을 사용한다.
- VR 일반 BUY ladder는 기존 `VrPosition.buyPrice(m)` 기준으로 생성하고, 생성 시점의 현재가 cap은 제거한다.
- 실제 접수 전 cap 보정에서 VR BUY 가격과 수량을 다시 만든다.

SELL bootstrap은 이번 설계에서 변경하지 않는다. SELL을 전일종가 기준으로 바꾸면 갭 하락일에 과도하게 낮은 매도 LOC가 생길 수 있으므로, BUY 문제 해결과 별도 판단으로 남긴다. 따라서 VR 첫 사이클 SELL 미리보기는 별도 요구가 생기기 전까지 현재 동작을 유지한다.

### 5. AT_OPEN 접수 경로 정리

`placeGiven()`은 현재 cap 없이 바로 접수한다. 개장 선접수 주문도 BUY cap이 필요하므로 다음 중 하나로 정리한다.

권장안은 `placeGiven()`을 전략 정보와 현재가를 받을 수 있는 메서드로 확장하는 것이다.

```text
placeGiven(today, orders, account, strategyCycleId, placementPrice, position, strategy)
```

이 경로도 `PriceCapMode`를 보고 `BuyOrderPriceCapper`를 호출한 뒤, 보정된 PLANNED 주문을 다시 조회해 접수한다. 마감 접수와 개장 접수의 BUY 보호 정책이 같아진다.

## 오류 처리

- 접수 직전 현재가 재조회가 실패하면 해당 ticker의 `placementPrice`는 `null`로 둔다.
- `placementPrice == null`이면 cap 보정은 생략하고 기존 PLANNED 주문을 접수한다.
- 가격 조회 실패는 기존 `TradingPriceFetcher`의 실패 격리와 알림 정책을 따른다.
- cap 보정 중 한 사이클에서 예외가 발생하면 기존 `runSafely("증권사 접수", ...)` 경계에서 해당 사이클만 실패 처리하고 다른 사이클은 계속 진행한다.

## 예산 배정 영향

현재 신규 후보는 `prepareForAllocation()`에서 cap 반영 후 예산 배정에 들어간다. 접수 직전 최신 현재가로 cap을 다시 적용하면, 저장 전 예산 검증 가격과 실제 접수 전 보정 가격이 달라질 수 있다.

처리 기준은 다음과 같다.

- 저장 전 `prepareForAllocation()`은 시작 시점 가격으로 보수적 예산 검증을 계속 수행한다.
- 접수 직전 cap 보정은 BUY 가격을 낮추는 방향만 허용한다.
- 보정 후 BUY 총액이 증가하는 전략별 재산정이 있다면, 접수 전 보정 결과도 계좌 예산을 다시 확인해야 한다.

INFINITE의 correction BUY 재산정은 원본보다 총액이 커질 수 있으므로 기존 테스트 패턴처럼 최종 BUY 총액 기반 예산 검증이 필요하다. VR도 bootstrap 수량 재계산으로 총액 변화가 가능하므로 같은 기준을 적용한다.

## 테스트 범위

- `StrategyOrderPlanBuilderTest`: VR 미리보기에서 전일종가를 조회하고 첫 사이클 BUY 주문이 생성되는지 검증한다.
- `VrCycleOrderStrategyTest` 또는 `VrStrategyTypeTest`: referencePrice 기반 bootstrap BUY 생성과 현재가 null 의존 제거를 검증한다.
- `BuyOrderPriceCapperTest`: `VR_POSITION` mode에서 cap 초과 VR BUY가 전략 공식으로 재산정되는지 검증한다.
- `TradingServiceTest`: 마감 접수 직전 ticker별 현재가를 일괄 재조회하고, 그 가격이 `placeOrders`에 전달되는지 검증한다.
- `TradingServiceTest`: 개장 AT_OPEN 선접수 경로도 cap 보정 경로를 통과하는지 검증한다.
- `TradingOrderExecutorTest`: `PriceCapMode`별 보정 호출과 보정 후 PLANNED 재조회 접수 순서를 검증한다.

## 구현 순서

1. VR 미리보기 실패를 재현하는 테스트를 추가한다.
2. VR 계획 가격 입력을 전일종가 기반으로 바꾼다.
3. VR의 생성 시점 현재가 cap을 제거하고 `PriceCapMode.VR_POSITION`을 추가한다.
4. `BuyOrderPriceCapper`에 VR 재산정 위임을 추가한다.
5. `TradingService`에 접수 직전 ticker 일괄 현재가 재조회 단계를 추가한다.
6. `placeOrders`와 `placeGiven` 계열 접수 경로를 같은 cap 보정 정책으로 정리한다.
7. focused test와 `./gradlew compileJava`를 실행한다.

## 승인 기준

- VR 미리보기 첫 사이클 BUY 주문이 전일종가 기준으로 생성된다.
- 실제 접수 전 BUY cap은 최신 일괄 조회 현재가로 계산된다.
- 동일 ticker 현재가는 접수 직전에 ticker당 1회만 조회된다.
- INFINITE, PRIVACY, VR 모두 `BuyOrderPriceCapper`를 통해 공통 cap 판단과 DB 교체 흐름을 사용한다.
- 전략별 재산정 공식은 각 전략 구현에 남아 있다.
- AT_OPEN과 AT_CLOSE 접수 경로의 BUY cap 정책이 일관된다.
