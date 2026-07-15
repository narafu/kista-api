# Admin Order Correction Design

## Goal

관리자 페이지에서 잘못 생성되었거나 잘못 체결된 주문을 상태별로 안전하게 보정한다.  
대상 선택은 `user -> account -> strategy -> order` 순으로 진행하고, 주문 상태(`PLANNED`, `PLACED`, `FILLED`)에 따라 보정 방식이 달라진다.

## Scope

- 관리자 보정 대상 주문 조회 API 추가
- `PLANNED` 주문 직접 수정 지원
- `PLACED` 주문의 브로커 취소 후 재주문 지원
- `FILLED` 주문의 차액 보정 체결 추가 지원
- 감사로그 기록

포함하지 않는 범위:

- 기존 체결 이력 덮어쓰기
- 주문 이력 삭제
- 일반 사용자용 보정 기능

## Current Context

- 관리자 계정/전략 관련 API는 `adapter/in/web/Admin*Controller` 아래에 있다.
- 수동 체결 보정은 이미 `AdminTradeCorrectionService`와 `POST /api/admin/trades/manual-fills`로 다건 fill 추가를 지원한다.
- 전략별 주문 이력은 `orders` 테이블과 `OrderPort`를 통해 조회/저장한다.
- 브로커별 주문 취소/접수는 현재 일반 매매 흐름에서 KIS/TOSS 어댑터를 통해 수행된다.

## Design

### 1. 조회 API

추가 API:

- `GET /api/admin/accounts/{accountId}/strategies/{strategyId}/orders?tradeDate=YYYY-MM-DD`

응답 필드:

- `orderId`
- `tradeDate`
- `ticker`
- `direction`
- `orderType`
- `timing`
- `price`
- `quantity`
- `status`
- `externalOrderId`
- `filledQuantity`
- `filledPrice`

목적:

- 관리자가 특정 전략의 특정 거래일 주문을 보고 보정 대상을 고른다.

### 2. 보정 실행 API

추가 API:

- `POST /api/admin/trades/order-corrections`

공통 입력:

- `userId`
- `accountId`
- `strategyId`
- `orderId`
- `mode`
- `tradeDateKst`
- `price`
- `quantity`
- `memo`

`mode` 값:

- `PLANNED_EDIT`
- `PLACED_REPLACE`
- `FILLED_CORRECTION`

### 3. 상태별 동작

#### `PLANNED_EDIT`

- 대상 주문은 반드시 `PLANNED`
- 같은 row의 `price`, `quantity`를 수정
- `externalOrderId`, `filledQuantity`, `filledPrice`는 건드리지 않음
- 감사로그 기록

#### `PLACED_REPLACE`

- 대상 주문은 반드시 `PLACED`
- 브로커 주문 취소 시도
- 취소 성공 시 기존 주문을 `CANCELLED`로 변경
- 새 주문을 같은 전략/거래일로 증권사에 재주문
- 새 주문 row를 `PLACED`로 저장하고 새 `externalOrderId`를 기록
- 브로커 취소 실패 시 DB 변경 없음
- 브로커 재주문 실패 시:
  - 기존 주문은 이미 취소 성공 상태이므로 `CANCELLED`
  - 새 주문 row는 생성하지 않음
  - 관리자 오류 알림 및 감사로그 기록

#### `FILLED_CORRECTION`

- 대상 주문은 반드시 `FILLED` 또는 `PARTIALLY_FILLED`
- 기존 주문 row는 수정하지 않음
- 차액 보정용 새 체결을 추가한다
- 결과적으로 `orders`, `cycle_position`, `strategy_cycle`, `strategy.status`를 재계산한다
- 현재 수동 체결 보정과 동일하게:
  - `SELL quantity > current holdings`는 거절
  - 청산되면 `strategy_cycle.end_amount/end_date` 갱신
  - 전략 상태는 안전하게 `PAUSED`

## Broker Integration

새 관리자 보정 전용 포트 추가:

- `BrokerOrderCorrectionPort.cancelPlacedOrder(...)`
- `BrokerOrderCorrectionPort.placeReplacementOrder(...)`

설계 원칙:

- `PLACED_REPLACE`는 브로커 성공 전 DB 선반영 금지
- KIS/TOSS 구현체는 기존 주문 실행 어댑터를 재사용하되, 관리자 보정에 필요한 최소 인터페이스만 노출
- 브로커 응답에서 취소/재주문의 외부 주문번호를 그대로 감사로그와 DB에 남긴다

## Data Rules

- `PLANNED`만 in-place 수정 허용
- `PLACED`와 `FILLED`는 이력 보존 우선
- 기존 주문 삭제 금지
- 모든 보정은 감사로그 필수

감사로그 payload 예시:

- `mode`
- `orderId`
- `accountId`
- `strategyId`
- `oldStatus`
- `oldPrice`
- `oldQuantity`
- `newPrice`
- `newQuantity`
- `brokerExternalOrderId`
- `memo`

## Failure Handling

- 잘못된 `mode`와 주문 상태 조합은 `400`
- `orderId`가 해당 `accountId/strategyId`에 속하지 않으면 `400`
- 브로커 취소 실패는 `409` 또는 `503` 계열로 매핑하되, DB 변경 없음
- 보정 도중 holdings 음수 유도 시 `400`

## Testing

### Service Tests

- `PLANNED_EDIT` 성공
- `PLANNED_EDIT` 상태 불일치 실패
- `PLACED_REPLACE` 취소 실패 시 DB 변경 없음
- `PLACED_REPLACE` 취소 성공 + 재주문 성공
- `FILLED_CORRECTION` 매수/매도 차액 보정
- `FILLED_CORRECTION` 청산 시 사이클 종료 + 전략 `PAUSED`

### Controller Tests

- 관리자 권한만 허용
- 요청 body validation
- `mode`별 정상 응답

### Regression Focus

- 기존 `manual-fills` 보정 흐름과 충돌하지 않을 것
- 기존 주문 조회/관리자 거래내역 조회 응답 형식이 깨지지 않을 것

## Recommended Implementation Order

1. 관리자 전략 주문 조회 API
2. `PLANNED_EDIT`
3. `FILLED_CORRECTION`
4. `PLACED_REPLACE` 브로커 취소/재주문 연동

## Risks

- `PLACED_REPLACE`는 브로커 취소/재주문 원자성이 완전하지 않다
- `FILLED_CORRECTION`은 실제 손익과 DB 손익이 어긋난 케이스를 다루므로 보정 입력 규칙이 엄격해야 한다
- 잘못된 관리자 조작 방지를 위해 대상 선택 체인(`user -> account -> strategy -> order`) 검증이 필수다
