# 리버스모드 쿼터매수 예산 소진 시 MOC 매도 전환 설계

## 배경

INFINITE 리버스모드(`ReverseInfiniteStrategy`)는 첫날 MOC 전량 청산 이후, 둘째 날부터 매일 별지점(`starPointPrice`) 기준으로 LOC 매도(`REVERSE_INFINITE_LOC_SELL`)와 LOC 쿼터매수(`REVERSE_INFINITE_LOC_BUY`)를 반복한다.

`ReverseModePosition.calcLocBuyQuantity()`는 쿼터매수 예산(`usdDeposit/4`)으로 별지점 아래 1주도 살 수 없으면 0을 반환하며, 현재 `ReverseInfiniteStrategy.buildOrders()`는 이 경우 매수 주문만 조용히 생략하고 LOC 매도는 그대로 유지한다. 이 상태에서는 청산이 LOC 매도 속도로만 진행되며 별도의 가속 수단이 없다.

`calcLocBuyQuantity()==0`은 서로 다른 두 상황에서 발생한다.

- (a) `starPointPrice`가 아직 계산되지 않은 경우(리버스모드 진입 초기, 직전 5거래일 종가 데이터 부족)
- (b) `starPointPrice`는 계산됐지만 쿼터매수 예산이 1주 가격에도 못 미치는 경우(예산 소진)

이번 설계는 (b) 예산 소진 상황만을 대상으로, 매수를 생략하는 대신 LOC 매도와 동일 수량을 MOC 매도로 전환해 청산을 가속한다.

## 목표

- `calcLocBuyQuantity()==0`이 예산 소진(b)으로 인한 경우, 해당 날짜의 리버스모드 주문을 LOC 쿼터매수 생략 + 동일 수량 MOC 매도로 대체한다.
- (a) 데이터 부족 상황은 기존 동작(무주문)을 그대로 유지한다.
- 판단 로직은 `ReverseModePosition`(Position, 상태 판단), 주문 조립은 `ReverseInfiniteStrategy`(Strategy, 주문 생성) 역할 분리를 그대로 따른다.

## 비목표

- LOC 매도 수량 계산식(`calcLocSellQuantity`) 자체는 변경하지 않는다.
- 리버스모드 종료(`shouldExitReverseMode`, 종가 회복) 조건은 변경하지 않는다.
- 첫날 MOC 청산(`buildFirstDayOrders`)의 동작·leg는 변경하지 않는다.
- `canSkipOrderComputation()`의 리버스모드 완료 판정 로직은 변경하지 않는다(아래 "기존 패턴과의 일관성" 참고).

## 설계

### 1. `ReverseModePosition`에 예산 소진 판정 predicate 추가

```java
// 예산 소진 판정: 별지점은 계산됐지만(데이터 존재) 쿼터매수 예산으로 1주도 못 사는 경우
// starPointPrice==null(별지점 데이터 미계산)은 별개 상태이므로 제외
public boolean isQuotaBuyExhausted() {
    return starPointPrice != null && starPointPrice.compareTo(BigDecimal.ZERO) > 0
            && calcLocBuyQuantity() == 0;
}
```

기존 `InfinitePosition.isFinalRound()`/`isEarlyStage()`와 동일한 TDA(Tell, Don't Ask) predicate 패턴을 따른다.

### 2. `ReverseInfiniteStrategy.buildOrders()` 분기

```java
public List<Order> buildOrders(ReverseModePosition position, LocalDate tradeDate) {
    if (position.isQuotaBuyExhausted()) {
        return buildQuotaExhaustedOrders(position, tradeDate);
    }

    List<Order> orders = new ArrayList<>();
    // ... 기존 LOC 매도 + LOC 쿼터매수 로직 ...
    return orders;
}

// 쿼터매수 예산 소진 — 매수 생략, 동일 수량(quarter) MOC 매도로 청산 가속
private List<Order> buildQuotaExhaustedOrders(ReverseModePosition position, LocalDate tradeDate) {
    int mocSellQuantity = position.calcLocSellQuantity();
    if (mocSellQuantity < 1) {
        log.warn("[리버스모드 예산소진] MOC 매도 수량 0 — holdings={}", position.holdings());
        return List.of();
    }
    log.info("[리버스모드 예산소진] 쿼터매수 불가 → MOC 매도 {}주", mocSellQuantity);
    return List.of(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity,
            BigDecimal.ZERO, "REVERSE_INFINITE_QUOTA_MOC_SELL"));
}
```

- 신규 `orderLeg`: `REVERSE_INFINITE_QUOTA_MOC_SELL` — 기존 `REVERSE_INFINITE_MOC_SELL`(첫날), `REVERSE_INFINITE_LOC_SELL`/`REVERSE_INFINITE_LOC_BUY`와 구분되는 별도 leg.
- 수량 공식은 `calcLocSellQuantity()`를 그대로 재사용한다(신규 계산식 추가 없음). `calcLocSellQuantity()`와 `calcMocSellQuantity()`는 이미 동일 식(`holdings / (divisionCount/2)`)이다.
- timing: `Order.planned(tradeDate, ticker, orderType, direction, quantity, price, orderLeg)` 6-인자 오버로드를 사용해 `AT_CLOSE` 기본값을 그대로 적용한다. 기존 LOC 매도/매수, 첫날 MOC 매도와 동일 타이밍이다.

### 3. 데이터 흐름

`CycleOrderComputer` → `InfiniteCycleOrderStrategy.planReverseMode()` → `ReverseModePosition.of(...)` → `reverseStrategy.buildOrders()` 순서는 변경하지 않는다. `ReverseModePosition`을 생성하는 데 필요한 입력(`starPointPrice`, `usdDeposit` 등)은 이미 존재하므로 상위 호출부(`InfiniteCycleOrderStrategy`, `CycleOrderComputer`)는 수정하지 않는다.

### 4. 기존 패턴과의 일관성 (`canSkipOrderComputation`)

`InfiniteCycleOrderStrategy.canSkipOrderComputation()`의 리버스모드 AT_CLOSE 완료 판정은 현재 `REVERSE_INFINITE_LOC_BUY` + `REVERSE_INFINITE_LOC_SELL` 두 leg가 함께 있어야만 완료로 인정한다. 첫날의 `REVERSE_INFINITE_MOC_SELL`은 이 조건에서 애초에 인식되지 않으므로, 첫날은 이미 스케쥴러가 매번 재계산을 시도하는 상태다.

신규 `REVERSE_INFINITE_QUOTA_MOC_SELL`도 별도 인식 로직을 추가하지 않고 기존 첫날 MOC와 동일하게 둔다. 재계산이 일어나도 `findPlannedOrPlacedByCycleAndDate` 기반 슬롯 점유 검사가 중복 주문 생성을 막으므로 기능적으로 안전하며, 기존에 검증된 첫날 MOC 처리 방식과 다르게 취급하지 않는 것이 일관성 측면에서 더 낫다고 판단했다.

## 오류 처리

- `mocSellQuantity < 1`이면 `log.warn` 후 빈 리스트를 반환한다(예외 없음) — 첫날 `buildFirstDayOrders()`의 기존 처리와 동일 패턴.
- `starPointPrice` 계산 실패(데이터 부족)는 기존과 동일하게 `isQuotaBuyExhausted()`가 `false`를 반환해 무주문 상태를 유지한다.

## 테스트 범위

`전략 테스트 분리 원칙`(도메인 변수 계산 vs 주문 생성 시나리오)에 따라 분리한다.

- `ReverseModePositionTest`(신규, `domain/model/strategy`): `isQuotaBuyExhausted()` 검증
  - 예산 소진(starPointPrice 존재 + calcLocBuyQuantity()==0) → `true`
  - starPointPrice `null`(데이터 부족) → `false`
  - 예산 충분(calcLocBuyQuantity()>0) → `false`
- `ReverseInfiniteStrategyTest`(기존 파일에 추가): `buildOrders()` 시나리오
  - 예산 소진 포지션 → `REVERSE_INFINITE_QUOTA_MOC_SELL` 단건 leg, MOC/SELL, 수량=`calcLocSellQuantity()` 검증
  - 예산 소진 + `mocSellQuantity==0` edge → 빈 리스트 검증

## 구현 순서

1. `ReverseModePositionTest`(신규)에 `isQuotaBuyExhausted()` 3케이스 실패 테스트 추가(TDD).
2. `ReverseModePosition.isQuotaBuyExhausted()` 구현.
3. `ReverseInfiniteStrategyTest`에 예산 소진 시나리오 테스트 추가(TDD).
4. `ReverseInfiniteStrategy.buildOrders()`에 분기와 `buildQuotaExhaustedOrders()` 구현.
5. `./gradlew test --tests 'com.kista.domain.strategy.*' --tests 'com.kista.domain.model.strategy.*'` 및 `./gradlew compileJava`로 검증.

## 승인 기준

- `starPointPrice`가 계산된 상태에서 쿼터매수 예산이 1주 가격에도 못 미치면, 그날 리버스모드 주문은 매수 없이 `calcLocSellQuantity()` 수량의 MOC 매도 1건만 생성된다.
- `starPointPrice`가 아직 계산되지 않은 데이터 부족 상황은 기존과 동일하게 무주문을 유지한다.
- 첫날 MOC 청산, 예산 충분 시 LOC 매도+쿼터매수 동작은 변경되지 않는다.
- 신규 leg(`REVERSE_INFINITE_QUOTA_MOC_SELL`)는 기존 leg와 겹치지 않고 명확히 구분된다.
