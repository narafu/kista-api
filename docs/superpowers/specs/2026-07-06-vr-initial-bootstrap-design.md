# VR Initial Bootstrap Design

## Goal

VR 전략 등록 시 초기 V값, 초기 시드, 주기당 입출금의 의미를 실제 투자 상태에 맞게 해석하고, 첫 사이클에서 TQQQ 보유 또는 현금만 있는 상태를 LOC 분할 주문으로 정상 부트스트랩한다.

## Domain Rules

- `initialValue`는 이미 보유한 TQQQ 평가금이다. `null`은 `0`으로 취급한다.
- `initialUsdDeposit`은 이미 있는 USD 예수금이며 VR에서는 초기 pool이다. `null`은 `0`으로 취급한다.
- `recurringAmount`는 향후 주기당 입출금 계획이다. 양수는 적립식, 0은 거치식, 음수는 인출식이다.
- 적립식(`recurringAmount > 0`)은 `initialValue=0`, `initialUsdDeposit=0`이어도 등록 가능하다.
- 거치식/인출식(`recurringAmount <= 0`)은 `initialValue + initialUsdDeposit > 0`이어야 등록 가능하다.
- 인출식(`recurringAmount < 0`)은 `initialValue + initialUsdDeposit >= abs(recurringAmount) * 100 * (4 / intervalWeeks)`이어야 등록 가능하다.
- `intervalWeeks >= 1`, `bandWidth > 0` 검증은 유지한다.
- 첫 사이클의 `poolLimit`은 `initialValue + initialUsdDeposit`에 `StrategyVrDetail.poolLimitRate()`를 곱한다.
- 첫 사이클 이후 롤오버 `poolLimit`은 기존처럼 `postBalance.usdDeposit * StrategyVrDetail.poolLimitRate()`를 유지한다.

## Initial Cycle Bootstrap Orders

- `initialValue > 0`, `initialUsdDeposit = 0`: 첫 사이클 종료일까지 `poolLimit`만큼 LOC 매도 주문을 남은 거래일 수로 나누어 생성한다.
- `initialValue = 0`, `initialUsdDeposit > 0`: 첫 사이클 종료일까지 `poolLimit`만큼 LOC 매수 주문을 남은 거래일 수로 나누어 생성한다.
- `initialValue = 0`, `initialUsdDeposit = 0`, `recurringAmount > 0`: due date 당일에만 `recurringAmount`만큼 LOC 매수 주문을 생성한다.
- 첫 사이클 bootstrap 주문은 모두 `OrderType.LOC`, `OrderTiming.AT_CLOSE`이다.
- 매수 LOC 가격은 `currentPrice * 1.10`으로 잡고, 매도 LOC 가격은 `currentPrice * 0.90`으로 잡는다.
- 첫 사이클 bootstrap 매수/매도 수량은 `dailyBudget / orderPrice`를 내림한 정수다. 수량이 0이면 주문을 만들지 않는다.
- 적립식 bootstrap 매수가 실패해 다음 사이클의 평가금이 0이면 새 사이클도 `V=0`으로 이어가며, 다음 due date에 다시 `recurringAmount` LOC 매수를 시도한다.
- 매수가 체결되어 평가금이 생기면 해당 평가금이 다음 사이클의 `V`가 된다.

## Architecture

- 등록 검증과 초기 스냅샷 저장은 `StrategyService`와 `VrStrategyLifecycle`에서 처리한다.
- 주문 생성은 기존 `VrStrategy`에 bootstrap 분기를 추가하되, 기존 사다리 주문 로직은 bootstrap이 아닌 일반 사이클에 그대로 유지한다.
- `CycleOrderComputer`는 VR 사이클 상세, 전략 상세, 현재 사이클 시작일, due date, 남은 거래일 수를 조립해 `VrCycleOrderStrategy`로 전달한다.
- 시장 개장일 계산은 `MarketCalendarPort.isMarketOpen(LocalDate)`를 재사용한다. 남은 거래일 수 계산 helper는 application layer에 둔다.
- 롤오버는 `VrCycleRolloverService`에서 유지하되, 적립식 bootstrap 실패 케이스에서는 `newValue <= 0`이어도 새 사이클을 `V=0`으로 생성한다.

## Testing Strategy

- `StrategyServiceTest`: VR 등록 검증과 첫 사이클 `poolLimit` 계산을 검증한다.
- `VrStrategyLifecycleTest`: `null` 값 정규화와 첫 사이클 poolLimit 기준을 검증한다.
- `VrStrategyTypeTest`: 첫 사이클 LOC bootstrap 매수/매도와 기존 사다리 주문 회귀를 검증한다.
- `CycleOrderComputerTest`: due date와 남은 거래일 수가 VR 주문 전략으로 전달되는지 검증한다.
- `VrCycleRolloverServiceTest`: 적립식 bootstrap 실패 시 `V=0` 새 사이클 허용과 일반 `V'<=0` 보류 정책을 구분해 검증한다.

