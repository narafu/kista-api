# DCA 다중 규칙 + DB 원장 재설계

- 작성일: 2026-10-21
- 선행 spec: `2026-10-13-dca-strategy-design.md`(주식 DCA), `archive`의 크립토 업비트 DCA spec, `2026-10-19-crypto-market-indicators-design.md`
- 범위: kista-api(trading-core) — 스키마·실행·API 계약. kista-ui 화면은 확정된 API 계약을 kista-ui 세션에 전달해 별도 진행

## 1. 의도

현재 DCA는 "매수 DCA"와 "매도 DCA"를 각각 별개 전략으로 등록한다. 보유수량·평단은 브로커 실잔고를 SSOT로 쓴다(`snapshotsBrokerBalance=true`). 이 구조에는 세 가지 한계가 있다.

- 매수와 매도를 묶는 규칙을 표현할 수 없다. 예: "SMA20 이하면 매수, 이상이면 매도", "평단 −면 매수, +20%면 매도".
- 통계·포트폴리오에서 DCA를 빼야 한다. 매수·매도 DCA가 같은 실잔고 스냅샷을 각자 기록해서 이중 집계되기 때문이다.
- 크립토 DCA는 `cycle_position`을 아예 기록하지 않는다.

이번 재설계로 **전략 1개 = 종목 1개 + 규칙 N개(매수·매도 혼합)**로 바꾼다. 보유·평단 SSOT는 다른 사이클 전략과 같은 **DB 원장(`cycle_position`)**으로 통일한다. 크립토도 포함한다.

### 대표 시나리오 (사용자 제시)

1. 토스 QLD — 매일 매수 + 매주 월요일 매도. 주식이라 실행 시점은 MOO/MOC로 지정한다.
2. 업비트 BTC — 매일 09시에 실행한다. 전일 종가 ≥ SMA20이면 매도하고, ≤ SMA20이면 매수한다.
3. 업비트 ETH(사용자 원문 "ETG", 현재 지원 종목은 BTC·ETH뿐이라 ETH로 해석)
   - 매일 09시: 종가 ≤ 평단 AND RSI ≤ 30이면 15,000원어치 매수한다. 원화가 부족할 때 거부할지, 남은 금액만큼 살지는 규칙 옵션이다.
   - 매일 10시: 종가 ≥ 평단 +20%이면 50,000원어치 매도한다. 보유가 부족할 때 잔량을 전부 팔지, 거부할지는 규칙 옵션이다.

## 2. 목표 / 비목표 / 실패 조건

**목표**
- 한 DCA 전략에 규칙을 여러 개 둔다. 규칙은 방향, 실행 주기, 실행 시점, 조건(AND), 규모, 부족 시 정책으로 이루어진다.
- 보유·평단을 DB 원장으로 관리한다. 매수 체결은 +, 매도 체결은 −다. 주식과 크립토가 공통이다.
- DCA 전략을 통계·포트폴리오 집계에 포함한다.
- DCA 수동 체결 보정을 연다(현재 400).

**비목표**
- 새 지표 추가. SMA·RSI·AVG_PRICE·FEAR_GREED를 그대로 쓴다.
- 매수·매도 상계나 우선순위. 동시에 발동하면 둘 다 주문한다(사용자 결정).
- 수량 직접 입력. 매도 규모는 금액 또는 보유 비율로만 받는다(사용자 결정).
- 운영 기존 DCA 데이터 이관. 기존 데이터는 테스트용이라 버린다(사용자 결정).
- kista-ui 화면 설계(별도 세션).

**실패 조건 (동작해도 실패로 본다)**
- 같은 입력(외부 체결 없음)에서 INFINITE·VR·PRIVACY의 주문 결과가 한 건이라도 달라진다. 매매 커널 공식의 의미는 변경 금지이고, R1은 타입만 바꾼다.
- 원장이 실잔고를 넘는 수량을 매도 주문한다.
- 같은 규칙이 같은 거래일에 두 번 주문된다. 재기동, 재개, 규칙 편집 중에도 마찬가지다.

## 3. 사용자 결정 사항

| 항목 | 결정 |
|---|---|
| 같은 시점 매수·매도 동시 발동 | **둘 다 각각 주문**한다 |
| 매도 규모 | **금액**(기본) 또는 **보유 비율 %**(100% = 전량). 수량 입력은 두지 않는다 |
| 부족 시 정책 | 규칙마다 고른다. 매수 부족: `PARTIAL`(남은 금액만큼) / `REJECT`(거부). 매도 부족: `PARTIAL`(잔량 전부) / `REJECT` |
| 원장 저장 | 원장 타입 전부를 **BigDecimal로 통일**한다(R1, 이번 범위): `cycle_position.holdings`(numeric), `CyclePosition`, `AccountBalance.holdings`, `AccountBalance.Fill.quantity`, 원장을 노출하는 응답·wire. 주식 전략(Infinite·VR·Privacy·백테스트) 공식은 **변경하지 않고** `AccountBalance.wholeShares()`(정수 주, 소수면 `ArithmeticException`)로 읽는다 — "주식 보유는 정수 주"라는 도메인 사실. 증권사 vendor 타입(`BrokerBalance`·`SellableQuantity`·`Execution`)과 FIDA 기준표(`PrivacyPlan`·privacy 계약)는 외부에서 정수로 정해지므로 int 유지 |
| 계획 경로 | DCA를 `CycleOrderStrategy.plan()` 정규 경로로 편입한다(R2, 이번 범위). 크립토는 실행 루프만 별도로 두고 판정 커널은 공유한다 |
| DCA 통계 원금 | **누적 순투입액** = 자기 주문 매수 체결 합계 − 매도 체결 합계. 수익 = 평가액 − 순투입액 |
| 계좌+종목 중복 | DCA도 다른 전략처럼 **계좌+종목당 1개**로 되돌린다(spec 2026-10-13 §3.4의 완화를 철회) |
| 실행 시점 | 규칙마다 지정한다. 주식은 MOO/MOC, 크립토는 executionHour(KST 0~23) |
| 예수금 | DCA의 `usd_deposit`은 0을 유지한다. 시드를 점유하지 않고 `calcFreeCash`에 영향이 없다 |
| 원장 원칙 | **원장은 우리 매매 원칙의 기록이지, 실계좌의 복제가 아니다.** 모든 전략(주식·크립토, 기존 INFINITE·VR·PRIVACY 포함)의 원장에는 **자기 주문 체결만** 반영한다. 외부(앱 직접) 매매는 수동 체결 보정으로만 원장에 들어간다. 원장과 실잔고가 달라도 알림을 보내지 않는다 |
| 드리프트 안전장치 | 매도 직전 브로커 판매가능수량을 실시간으로 확인하는 것을 유지한다. 목적은 원장 일치가 아니라 주문 거부 방지다 |
| 기존 운영 DCA | 이관하지 않고 배포 전에 정리한다 |
| UI | kista-ui 세션에 API 계약을 전달해 요청한다 |

## 4. 도메인 모델

### 4.1 규칙 (`DcaRule`, matching 커널 — 기존 `DcaConfig` 대체)

```
DcaRule(
  String ruleKey,            // 버전 간 안정 식별자 — 주문 leg·회차 이력 키 (§5.3)
  OrderDirection direction,  // BUY | SELL
  DcaFrequency frequency, DayOfWeek dayOfWeek, Integer dayOfMonth,  // 기존 주기 규칙 그대로
  OrderType orderType,       // 주식 MOO|MOC, 코인 MARKET
  Integer executionHour,     // MARKET 전용 필수 (KST 0~23)
  DcaSizing sizing,          // AMOUNT(amount) | HOLDING_PERCENT(percent)
  DcaShortfallPolicy shortfall, // PARTIAL | REJECT
  List<DcaCondition> conditions // 기존 DcaCondition 그대로 (AND, 최대 10)
)
```

- `DcaSizing`
  - `AMOUNT(amount > 0)`: 계좌 통화다(주식 USD, 코인 KRW 원 단위 정수, 5,000 이상).
  - `HOLDING_PERCENT(0 < percent ≤ 100)`: **SELL에서만 허용**한다.
- 기존 `DcaConfig`의 검증 규칙(주기, 주말 요일, MARKET↔executionHour, `requireCompatible(Market)`)은 규칙 단위로 옮긴다.
- 전략 설정 = `List<DcaRule>`. 1~10개이고 입력 순서를 보존한다. `ruleKey`는 전략 안에서 유일하다.
- 신규 enum(`DcaSizingType`, `DcaShortfallPolicy`)은 sharedkernel에 둔다. 기존 `DcaIndicator`·`DcaFrequency`와 같은 위치이고, `/api/meta`에 노출한다.

### 4.2 판정 커널

`DcaCycleOrderStrategy.decide`는 규칙 1개를 판정한다. 배치는 실행일·시점이 맞는 규칙마다 호출한다. 판정 순서는 기존과 같다.

1. 데이터 → 조건 → 보유(SELL) → 규모 → 부족 정책 순으로 판정한다.
2. AVG_PRICE 조건의 평단은 **원장 평단**이다. 브로커 실잔고 조회 경로(`loadHolding` → `LiveBalancePort`)는 제거한다.
3. 규모 → 수량
   - BUY AMOUNT(주식): `floor(amount / 기준가)` 주. 기준가는 기존과 같이 max(전일 종가, 현재가)이고, 지정가는 캡가다.
   - SELL AMOUNT(주식): `floor(amount / 전일 종가)` 주.
   - SELL HOLDING_PERCENT(주식): `floor(원장 보유 × percent / 100)` 주.
   - 크립토 BUY: 금액 주문. 크립토 SELL AMOUNT: `amount / 현재가`(8자리 FLOOR). 크립토 SELL HOLDING_PERCENT: `원장 보유 × percent / 100`(8자리 FLOOR).
4. 매도 가용량 = `min(원장 보유, 브로커 판매가능수량 − 같은 계좌·거래일·종목 PLANNED/PLACED SELL 예약분)`.
   - 계산 수량이 가용량을 넘으면 `PARTIAL`은 가용량으로 줄이고, `REJECT`는 skip(`NO_HOLDINGS` 또는 신규 `INSUFFICIENT_HOLDINGS`)한다.
   - 가용량이 0이면 정책과 무관하게 `NO_HOLDINGS`다.
5. 매수 예산 부족
   - 주식: allocator 단계에서 판정한다(§5.4).
   - 크립토: `CryptoDcaRoundExecutor`의 지갑 단계에서 판정한다. `PARTIAL` = 기존 `min(amount, 원화가용/수수료)`이고, `REJECT` = 원화가용이 부족하면 `INSUFFICIENT_BALANCE` skip이다.
6. 최소 단위 미만(1주, 5,000원)은 기존과 같이 `BELOW_ONE_SHARE` / `BELOW_MIN_ORDER_AMOUNT`로 skip한다.

### 4.3 같은 시점 다중 발동

- 같은 전략에서 같은 거래일·같은 시점에 발동한 규칙은 **모두 각자 주문**한다. 상계는 없다.
- 크립토 같은 틱에서는 규칙 처리 순서를 **SELL 먼저, BUY 나중**으로 한다(같은 방향 안에서는 입력 순서). 매도 대금이 원화가용에 반영되는지는 업비트 체결 시점에 달렸다. 지갑의 로컬 차감은 매도 대금을 더하지 않는다(보수적).
- 주식 MOO/MOC는 동시 접수라 순서와 무관하다.
- 같은 방향 규칙이 둘 이상 동시에 발동하면, SELL 가용량은 앞 규칙이 예약한 수량을 빼고 계산한다. 기존 예약분 차감 로직과 같은 원리다.

## 5. 저장과 실행

### 5.1 스키마 (trading-core `db/migration-trading`, V11~)

- `trading.strategy_dca_version`을 **규칙 테이블로 대체**한다.
  - `trading.strategy_dca_rule`의 컬럼:
    - `id`, `strategy_version_id`(FK → strategy_version, CASCADE), `rule_key`, `sort_order`
    - `direction`, `frequency`, `day_of_week`, `day_of_month`, `order_type`, `execution_hour`
    - `sizing_type`, `amount numeric(20,2)`, `percent numeric(5,2)`, `shortfall_policy`
    - `created_at`
  - 제약: UNIQUE(`strategy_version_id`, `rule_key`), UNIQUE(`strategy_version_id`, `sort_order`). 기존 주기·시각 CHECK를 옮기고, `sizing_type`별 amount/percent 배타 CHECK를 추가한다.
- `trading.strategy_dca_condition`의 FK를 `strategy_dca_rule(id)`로 바꾼다(규칙 1:N 조건).
- `trading.dca_execution`
  - `rule_key varchar(36) NOT NULL`을 추가한다.
  - 유일키를 `(strategy_id, trade_date)`에서 `(strategy_id, rule_key, trade_date)`로 바꾼다.
- `trading.cycle_position.holdings`: `integer`를 `numeric(20,8)`로 바꾼다.
  - 엔티티와 `CyclePosition.holdings`는 `BigDecimal`이 된다.
  - 커널 `AccountBalance.holdings`도 BigDecimal이다(R1). 주식과 크립토가 같은 `applyExecutions`로 원장을 갱신한다.
- 기존 DCA 데이터: 이관하지 않는다. 마이그레이션이 DCA 전략과 그 종속 행(버전·사이클·포지션·주문·이력)을 하드 삭제한다(사용자 결정 "배포 전 정리"를 마이그레이션에 고정). 정확한 삭제 SQL과 순서는 구현에서 정하고, 운영 적용 전에 대상 건수를 SELECT로 확인한다.
- trading-core 단독 스키마라 root expand/contract 제약 밖이다. 단, V9와 같이 **이미지 롤백 불가** 주석을 남긴다.

### 5.2 원장 갱신

- **주식**: 기존 `TradingReporter`의 마감 리포트 경로를 쓴다. `snapshotsBrokerBalance` 분기와 `liveSnapshot`은 **제거**한다. `usd_deposit`은 0을 강제한다(E8에서 능력 메서드로 대체).
  - 자기 주문(당일 자기 주문의 `externalOrderId`) 체결 필터는 DCA 전용이었는데, 이를 **모든 전략 공통 규칙으로 승격**한다.
  - 현재 INFINITE·VR·PRIVACY는 `getExecutions`(계좌+종목 당일 체결 전체)를 그대로 원장에 반영한다. 그래서 사용자가 앱에서 직접 한 매매가 섞인다(`TradingReporter.java:66~71`).
  - 이 변경으로 기존 전략의 동작이 바뀐다. 앱 직접 매매가 더 이상 원장에 자동 반영되지 않는다.
  - 회귀 잠금: "외부 체결이 섞인 executions가 들어와도 원장이 변하지 않는다" 케이스를 테스트로 둔다. 수동 실행(`ManualTradingService`) 주문은 자기 주문이므로 반영되는지 확인한다.
  - 결과: 장중 MOO 매수 체결은 같은 날 MOC 매도 규칙의 `HOLDING_PERCENT`·AVG_PRICE 계산에 반영되지 않는다. 원장은 마감 리포트에서 갱신된다. 의도된 한계다.
- **크립토**: `CryptoOrderSynchronizer`가 주문을 FILLED(또는 PARTIALLY_FILLED 확정)로 전이할 때 그 체결을 원장에 반영한다. 반영 방식은 `cycle_position` 새 행 추가이고, 최신 행이 SSOT다.
  - 같은 날 09시 매수 체결이 10시 매도 규칙의 평단에 반영된다(시나리오 3).
  - 크립토는 `closing_price`를 체결 시점 가격 또는 null로 기록한다. 정확한 값은 구현에서 정한다.
- 시작 원장: 등록할 때 `initialHoldings`/`initialAvgPrice`를 받는다. 크립토는 소수를 허용하고, 주식은 정수만 받는다. `BootstrapPosition`을 재사용하되 크립토 소수 검증은 확장한다.
- `CycleOrderStrategy.snapshotsBrokerBalance()`는 호출처가 없어지므로 삭제한다.

### 5.3 규칙 식별과 중복 방지

- 주문 leg = `"DCA_" + ruleKey`다(`orders.order_leg varchar(50)`에 들어간다).
  - 기존 `DcaConfig.orderLeg()`(`DCA_BUY`/`DCA_SELL`)를 대체한다.
  - `order.orderLeg().startsWith("DCA_")` 판별은 그대로 동작한다.
- 같은 날 같은 규칙의 주문과 이력 중복 방지는 기존 로직을 규칙 키 단위로 바꿔서 쓴다. 복구 경로도 같다(`DcaRoundPlanner`·`CryptoDcaRoundExecutor`의 기존 주문 → ORDERED 복구, `TradingOrderSlots.excludeExisting`).
- **ruleKey는 수정 시 보존한다**. 서버가 신규 규칙에 UUID를 발급한다. 수정 요청에서 클라이언트가 기존 규칙의 `ruleKey`를 다시 보내면 같은 규칙으로 보고, 생략하면 신규 규칙이 된다. 그래서 장중에 규칙을 편집해도 이미 판정·주문된 규칙이 다시 발동하지 않는다.
  - 요청한 ruleKey가 현재 버전에 없으면 400이다.

### 5.4 주식 배치 (allocator)

- 한 DCA 후보가 여러 주문(규칙별)을 가질 수 있다. 예: 같은 MOO에 BUY 2건과 SELL 1건.
- `fitBuysToBudget`는 `REJECT` 규칙의 BUY는 줄이지 않고 통째로 빼고, `PARTIAL` 규칙의 BUY만 `floor(budget / 캡가)`로 줄인다.
  - 규칙 정책은 주문 leg로 찾는다.
  - 커널이 정책을 알 수 있도록 `PlannedOrder`에 플래그를 싣거나, 후보에 leg→정책 맵을 싣는다. 방식은 구현에서 정한다.
- allocator 거절과 축소 결과를 **주문(leg) 단위**로 이력에 기록한다. 거절된 규칙은 `INSUFFICIENT_BALANCE`/`NO_HOLDINGS`로 기록하고, 승인된 규칙은 ORDERED로 기록한다.
  - 현재 `recordDcaRejections`·`recordDcaOrdered`는 후보(전략) 단위다. 이를 leg 단위로 바꾼다.
- `allocationPriority()=200`(다른 전략 예산 우선)을 유지한다.

### 5.5 크립토 배치

- `CryptoDcaRunner`/`CryptoDcaRoundExecutor`의 대상 단위를 "전략"에서 "전략×규칙"으로 바꾼다.
- 대상 조건: `executionHour == 현재 시`, 실행일, 그 규칙의 당일 이력·주문이 없음.
- 지갑 로컬 차감, 캔들·공포탐욕 메모, 키 만료 일괄 처리, TRANSIENT 처리는 그대로 둔다.
- 매도 가용량 = `min(원장 보유, 지갑 held)`다. 업비트 `available`이 실시간 판매가능수량 역할을 한다.

### 5.6 수동 체결 보정

- `ManualTradeCorrectionService`의 DCA 400 가드를 제거한다.
- 보정은 원장(`cycle_position`)에 반영한다. DCA는 영구 사이클이라 `endsCycleOnLiquidation=false`이고, 청산해도 사이클은 유지된다.
- 크립토 소수 수량 보정이 가능하도록 `ManualTradeCorrectionCommand.Fill.quantity` 타입과 검증을 확인하고, 필요하면 넓힌다.

### 5.7 통계·포트폴리오

- `TradingStatsService`·`InvestmentPointsQueryService`의 `!s.isDca()` 제외 필터를 제거한다.
- DCA 원금 = 누적 순투입액(자기 주문 매수 체결 합계 − 매도 체결 합계, 수동 보정 포함)이다. DCA는 시드(`startAmount`)가 없으므로 원금 계산을 `CycleOrderStrategy` 능력으로 분기한다. 제외 필터만 지우면 매수가 수익으로 잡히므로 반드시 함께 바꾼다. 저장 방식(조회 시 집계 또는 원장 행 컬럼)은 빌더 재량이다.
- 크립토 DCA는 통화가 KRW다. 기존 통계는 USD 전제일 수 있으므로, 계좌 통화별로 분리 집계되는지 구현 시 확인한다. 섞이면 크립토 계좌는 제외 상태를 유지하고 후속 과제로 남긴다. 이 판단은 구현자가 하고 보고한다.
- 프리뷰(`TradingPreviewService`)의 DCA 제외는 유지한다(범위 밖).

## 6. API 계약 (kista-ui 전달 대상)

등록·수정 요청의 `dca` 블록을 바꾼다.

```json
"dca": {
  "rules": [
    {
      "ruleKey": null,                // 신규 null, 수정 시 기존 값 재전송
      "direction": "BUY",
      "frequency": "DAILY", "dayOfWeek": null, "dayOfMonth": null,
      "orderType": "MARKET",          // 주식 MOO|MOC, 코인 MARKET
      "executionHour": 9,             // 코인 필수, 주식 무시
      "sizing": { "type": "AMOUNT", "amount": 15000, "percent": null },
      "shortfall": "REJECT",          // PARTIAL | REJECT
      "conditions": [ { "indicator": "AVG_PRICE", "operator": "LTE", "smaPeriod": null, "threshold": 0 },
                      { "indicator": "RSI", "operator": "LTE", "smaPeriod": null, "threshold": 30 } ]
    }
  ]
}
```

- 등록 요청에 `initialHoldings`(크립토는 소수 문자열이나 숫자 허용)와 `initialAvgPrice`를 DCA에도 허용한다.
- 조회 응답 `dca`
  - `rules[]`: 요청 필드와 같은 구조에 `ruleKey`가 확정값으로 들어간다. 규칙마다 `nextExecution`과 `lastExecution`을 둔다.
  - 전략 수준에서는 `currentHoldings`(BigDecimal)와 `avgPrice`를 원장 기준으로 응답한다.
- 회차 이력(`DcaExecutionPageResponse.Item`)에 `ruleKey`와 `direction`을 추가한다.
- `/api/meta`에 `DcaSizingType`과 `DcaShortfallPolicy` 라벨을 추가한다.
- 구 단일 규칙 필드(`direction`, `amount` 등 최상위)는 제거한다. 보내면 400이다. 운영 DCA가 테스트 데이터라 호환 기간을 두지 않는다.
- 이 계약은 **kista-api 배포와 kista-ui 배포가 짝**이다. 구 UI는 DCA 등록이 깨진다.

## 7. 오류·알림

- skip 사유: 기존 `DcaSkipReason`을 재사용한다. 매도 `REJECT` 정책으로 거부된 경우는 신규 사유(`INSUFFICIENT_HOLDINGS`)를 추가할지, `NO_HOLDINGS`의 detail로 구분할지 구현에서 정한다(내 판단으로는 신규 상수가 낫다 — UI가 구분 표시 가능).
- 알림(`DcaRoundSkippedEvent` 등)은 규칙 단위로 발행하고, 메시지에 방향과 규칙 순번을 포함한다.

## 8. 테스트

- 회귀 잠금: INFINITE·VR·PRIVACY 기존 단위·통합 테스트가 holdings 타입 변경 후 변경 없이 통과해야 한다. 백테스트(`BacktestEngine`)는 고정 입력에서 R1 전후 결과가 같아야 한다(골든 비교).
- 커널: 규칙별 `decide` 테스트를 둔다(금액·비율 × PARTIAL·REJECT × 원장·판매가능 불일치). 같은 시점 BUY+SELL 동시 발동 테스트를 둔다.
- 크립토: 09시 매수 체결 → 원장 갱신 → 10시 AVG_PRICE 매도 판정 시나리오(시나리오 3)를 통합 테스트(`*IT`)로 확인한다.
- 중복 방지: 규칙 편집(ruleKey 보존) 후 같은 날 재발동이 없는지, 재기동 복구가 규칙 단위로 되는지 확인한다.
- allocator: PARTIAL BUY는 축소하고 REJECT BUY는 제외하며, 이력이 leg 단위로 기록되는지 확인한다.
- 마이그레이션: 로컬 DB에서 holdings 타입 변경과 DCA 정리를 실행해 보고, `ddl-auto: validate`로 기동하는지 확인한다.

## 9. 일관성 원칙과 남는 예외 (사용자 원칙: 예외 최소화, 필요하면 기존 코드도 과감히 리팩토링)

이 재설계 후에도 DCA가 다른 전략과 다르게 동작하는 지점과 각 처리안을 정리한다. 모두 사용자가 확정했다.

| # | 예외 지점 | 처리 |
|---|---|---|
| E1 | 원장 SSOT가 브로커 실잔고 | **확정 해소** — DB 원장으로 통일 |
| E2 | 계좌+종목 중복 허용 | **확정 해소** — 계좌+종목당 1개 |
| E3 | 통계·포트폴리오 제외 | **확정 해소** — 포함. 크립토 KRW 통화 분리 여부만 확인 대상 |
| E4 | 수동 체결 보정 400 | **확정 해소** — 개방 |
| E5 | `snapshotsBrokerBalance`·DCA 전용 자기 주문 체결 필터 | **확정 해소** — 스냅샷 삭제, 필터는 전 전략 공통 규칙으로 승격 |
| E6 | holdings 타입: 저장은 BigDecimal, 커널은 int(A′) | **확정 해소** — R1 |
| E7 | DCA가 `CycleOrderStrategy.plan()`을 우회한다(`UnsupportedOperationException`, `TradingCandidatePlanner`의 `isDca()` 분기 6곳) | **확정 해소** — R2 |
| E8 | DCA `usd_deposit=0` 강제 | 유지 — DCA는 시드 개념이 없다. 다만 "시드 점유 여부"를 `CycleOrderStrategy`의 능력 메서드로 올려 리포터의 `isDca()` 분기를 없앤다(확정) |
| E9 | 프리뷰에서 DCA 제외 | **확정 해소** — R2로 같은 `plan()` 경로가 되므로 포함한다 |
| E10 | 원장 갱신 시점: 주식은 마감 리포트, 크립토는 체결 동기화 | 유지 — 체결 확인 수단이 다르다(배치 vs 24/7 폴링). 원장 반영 함수(`applyExecutions` → `cycle_position` 저장)는 하나로 공유한다(확정) |

**R1. 원장 holdings를 BigDecimal로 통일 (E6 해소)**
- 원장 타입(`AccountBalance.holdings`·`Fill.quantity`·`CyclePosition`·DB·응답)을 BigDecimal로 바꾼다. 주식과 크립토가 같은 `applyExecutions`를 쓴다.
- 주식 전략 공식(정수 나눗셈: `InfinitePosition` 1/4 매도, `VrPosition`·`VrStrategy` 사다리 분모, `ReverseModePosition` 분할)은 그대로 둔다. 대신 `AccountBalance.wholeShares()`로 읽는다. 소수 보유가 주식 공식에 닿으면 조용히 계산하지 않고 즉시 예외를 낸다.
- `== 0`·`> 0` 같은 원장 비교는 `signum()`으로 바꾼다.
- **확정: 이번 범위에 포함한다(Plan 1).** 첫 단계에서 현재 HEAD의 백테스트·배치 주문 결과를 골든 테스트로 고정하고, 타입 변경 후 같은 결과가 나오는지 확인한다.
- 운영 근거(2026-10-21 읽기 전용 조회): 최근 30일 INFINITE·VR·PRIVACY의 `cycle_position` 보유 변화 106건이 모두 같은 날 자기 주문 FILLED 수량 합과 일치했다(불일치 0건). 자기 주문만 반영하도록 전환해도 기존 원장은 바뀌지 않는다.

**R2. DCA를 `CycleOrderStrategy.plan()` 정규 경로로 편입 (E7·E9 해소)**
- `OrderPlan`이 "주문 목록 + 규칙별 skip 사유"를 담을 수 있게 확장한다.
- DCA 전용 진입(`DcaRoundPlanner → decide`)과 `TradingCandidatePlanner`의 `isDca()` 분기를 없앤다.
- 지표 입력(캔들, 공포탐욕, 판매가능수량)은 PlanContext 입력 수집기로 일반화한다.
- 이번 재설계로 규칙이 N개가 되면서 DCA 전용 경로가 어차피 크게 바뀐다. 그래서 **같은 작업에서 하는 것이 총비용이 가장 적다**.
- **확정: 이번 범위에 포함한다.** 크립토 `CryptoDcaRoundExecutor`는 실행 루프(시각 틱, 지갑 차감, 업비트 주문)를 유지하고, 규칙 판정과 주문 계획은 같은 커널을 호출한다.

## 10. 빌더 재량

- 클래스·패키지 배치. 기존 DCA 클래스의 개명·분할 여부를 포함한다.
- `PlannedOrder` 정책 전달 방식(§5.4).
- 크립토 원장 행의 `closing_price` 값, 체결 → 원장 반영의 트랜잭션 경계.
- 정리 마이그레이션 SQL의 세부 내용.
- `flows.yml`, `docs/agents/modules/tradingcore/trading.md`, `matching.md` 동기화. constraints.md의 flows.yml 규칙에 따라 같은 작업에서 갱신한다.
