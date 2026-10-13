# DCA(적립식 분할매매) 전략 설계

- 작성일: 2026-10-13
- 상태: 단계 ①·② 구현 완료(feat/dca-stage2), 단계 ③ 미착수
- 요청: kista-ui 세션(kista-ui-d9)에서 확정한 요구사항 기반. 이 문서는 kista-api 백엔드 설계만 다룬다.

## 1. 목표와 범위

정해진 주기마다 고정 USD 금액만큼 한 종목을 사거나 파는 전략 `StrategyType.DCA`를 추가한다. 회차마다 선택적 조건(평단가·이평선·RSI·공포탐욕지수)을 AND로 평가해, 미충족이면 그 회차는 건너뛴다.

### 사용자 확정 요구사항 (원문 요약)

1. 신규 `StrategyType.DCA`. 대상 종목 UPRO, QLD — `StrategyTicker`에 추가하되 DCA 전용(기존 전략 capability에는 미포함).
2. 전략 1개 = 단방향(BUY 또는 SELL). 같은 계좌·티커에 매수 DCA와 매도 DCA 동시 등록 허용.
3. 주기: DAILY / WEEKLY(요일 지정) / MONTHLY(1~31일 또는 말일).
   - 해당 월에 지정일이 없으면 그 달 말일로 보정.
   - 지정일이 휴장이면 다음 거래일로 이월하되 같은 기간(주/월) 안에서만. 기간 안에 이후 거래일이 없으면 직전 거래일로 당김.
   - DAILY는 매 거래일. 날짜 기준은 미국 거래일.
4. 회차당 고정 금액(USD). 주문유형은 MOO 또는 MOC만. MOO는 개장 배치, MOC는 마감 배치에서 전송. 수량 = floor(금액 / 전일 종가).
5. 잔고: 매수 예수금 부족 시 가능한 만큼 축소, 매도 보유 부족 시 보유 전량. 0주면 회차 skip + 알림. 1주 미만 금액도 skip + 알림. 이월·합산 없음. MOC 매수가 종가 변동으로 금액을 소폭 초과하는 것은 허용. 종료일·총 한도 없음(일시정지·삭제로 종료).
6. 추가 조건 0개 이상, AND 결합. 조건 = 지표 / 연산자(LTE, LT, GTE, GT) / 기준값.
   - 평단가: 전일 종가 vs 평단가 × (1 + n%). 보유 0주면 미성립. 단, 매수 DCA는 보유 0주일 때 평단 조건을 무시하고 매수.
   - 이평선: 전일 종가 vs SMA(20/50/120/200). 기준값 없음.
   - RSI: RSI(14) vs 숫자.
   - 공포탐욕지수(CNN): 지수 vs 0~100.
   - 평가 시점: 배치 실행 시 전일 종가 기준(MOO·MOC 동일).
   - 데이터 확보 실패(일봉 부족, CNN 장애 등)면 해당 회차 미주문 + 알림(fail-safe).

### 설계 중 사용자 결정 사항

| 항목 | 결정 |
|---|---|
| 매수 주문유형 | KIS·Toss 모두 미국 **매수** MOO/MOC를 지원하지 않는다(아래 2.2). UI 선택지는 MOO/MOC를 유지하고, 매수는 내부적으로 캡 지정가(LOO/LOC)로 변환한다. |
| 공포탐욕지수 경로 | trading-core가 CNN을 직접 조회한다(root `kista_ref` 복제 없음). |
| MOO 접수 시각 | 개장 배치를 22:30 → 22:00 KST로 당긴다. KIS DCA 개장 주문은 개장 전에 접수한다. |

### 범위 밖

- 종료일, 총 투자 한도, 미집행 금액 이월·합산
- DCA 백테스트, 사이클 성과·equity-curve 통계 반영(DCA는 통계 집계 대상에서 제외)
- 수동 실행(`/execute`)과 미리보기의 DCA 지원 — 1차에서는 DCA에 대해 409로 거부한다
- 조건 OR 결합

## 2. 사전 조사 결과 (설계 근거)

### 2.1 현재 구조에서 영향받는 지점

- `StrategyType.capability()`의 INFINITE가 `EnumSet.allOf(StrategyTicker.class)`라서, 티커를 추가하면 INFINITE에 자동으로 포함된다. 기존 4종(MAGX, USD, TQQQ, SOXL)을 명시한 집합으로 바꾼다.
- `StrategyCreationService.validateUniqueTicker`는 계좌+티커당 전략 1개를 강제한다. 주석의 근거는 "종목별 합산 잔고 ↔ 전략 일대일". DB에는 유니크 제약이 없고 서비스 검증만 있다.
- `orders.strategy_cycle_id`는 NOT NULL이다. 따라서 DCA에도 사이클이 필요하다.
- `CycleOrderStrategy.plan()`은 skip할 때 `Optional.empty()`만 반환하므로 skip 사유를 전달할 수 없다.
- 공포탐욕지수는 root `kista_ref.fear_greed_snapshots`(KST 00:00/12:00 수집)에 있고, trading-core는 root를 호출하지 않는다.
- 일봉은 `broker.CandlePort`(Toss 공용 피드, 관리자 자격증명 사용)로 받는다. 한 번에 최대 200봉이고, 봉의 `date()`는 US 세션일이다.
- `UsTradeDates`(KST↔US 변환)는 ArchUnit allowlist에 있는 어댑터 4곳에서만 쓸 수 있다.

### 2.2 브로커별 주문유형 제약

- **KIS 미국 주문 ORD_DVSN**
  - 매수: `00`(지정가), `32`(LOO), `34`(LOC)
  - 매도: `00`, `31`(MOO), `32`(LOO), `33`(MOC), `34`(LOC)
  - 즉 MOO와 MOC는 매도 전용이다. 코드베이스의 현재 MOC 사용처(`ReverseInfiniteStrategy`)도 전부 SELL이다. KIS 공식 포털에서 한 번 더 확인할 것(구현 Task 0).
- **Toss**
  - 시장가를 지원하지 않는다. 현재 `TossOrderApi`는 MOC를 `LIMIT + CLS @ 0.01`로 흉내 낸다.
  - 이 방식은 매도에서만 시장가처럼 동작한다. 매수에 쓰면 체결되지 않는다.
  - 개장 경매용 TIF는 확인되지 않았다.
- **MOCK**: `MockBrokerAdapter`는 MOC를 항상 체결로 처리한다.

## 3. 도메인 모델과 스키마

### 3.1 shared.kernel 어휘

- `StrategyType.DCA("적립식 분할매매")`
- `StrategyTicker.UPRO`, `StrategyTicker.QLD`
  - `targetProfitRate`는 DCA에서 쓰지 않는다. 생성자 형태를 유지하기 위해 `BigDecimal.ZERO`로 둔다.
  - `description`: "PROSHARES ULTRAPRO S&P500 3X", "PROSHARES ULTRA QQQ 2X"
  - `KisExchangeRegistry`에 두 종목의 거래소 코드를 추가한다(둘 다 NYSE Arca — 구현 시 KIS 종목정보로 확인).
- `OrderType.MOO` (Market On Open)
- 신규 enum (DB 저장 상수명은 바꾸지 않는다):
  - `DcaFrequency`: DAILY, WEEKLY, MONTHLY
  - `DcaIndicator`: AVG_PRICE, SMA, RSI, FEAR_GREED
  - `DcaOperator`: LTE, LT, GTE, GT
  - `DcaSkipReason`: DATA_UNAVAILABLE, CONDITION_NOT_MET, NO_HOLDINGS, INSUFFICIENT_BALANCE, BELOW_ONE_SHARE
- `StrategyCapability` 확장(아래 7.4)
- INFINITE capability를 명시 집합 `EnumSet.of(MAGX, USD, TQQQ, SOXL)`로 바꾼다.
- DCA capability: `EnumSet.of(UPRO, QLD)`, tickerFixed=false, divisionCounts=빈 목록

DCA 전용 enum은 matching 커널과 root `/api/meta`가 함께 쓰므로 shared.kernel에 둔다(INFINITE/VR 어휘와 같은 이유).

### 3.2 테이블 (trading-core `db/migration-trading`, 다음 번호)

`strategy`에는 컬럼을 추가하지 않는다. 기존 `strategy_vr_version`처럼 버전에 1:1로 붙는 상세 테이블을 둔다.

```sql
CREATE TABLE trading.strategy_dca_version (
    strategy_version_id uuid NOT NULL PRIMARY KEY,           -- FK → strategy_version.id ON DELETE CASCADE
    direction           varchar(20)   NOT NULL,              -- BUY | SELL
    amount_usd          numeric(20,2) NOT NULL CHECK (amount_usd > 0),
    order_type          varchar(20)   NOT NULL,              -- MOO | MOC
    frequency           varchar(20)   NOT NULL,              -- DAILY | WEEKLY | MONTHLY
    day_of_week         varchar(20),                         -- WEEKLY 전용 (java.time.DayOfWeek 이름), 그 외 NULL
    day_of_month        integer CHECK (day_of_month BETWEEN 1 AND 31), -- MONTHLY 전용, NULL+MONTHLY = 말일
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trading.strategy_dca_condition (
    id                  uuid NOT NULL PRIMARY KEY,
    strategy_version_id uuid NOT NULL,                       -- FK → strategy_dca_version ON DELETE CASCADE
    sort_order          integer NOT NULL,                    -- UI 입력 순서 보존
    indicator           varchar(20) NOT NULL,                -- AVG_PRICE | SMA | RSI | FEAR_GREED
    operator            varchar(20) NOT NULL,                -- LTE | LT | GTE | GT
    sma_period          integer,                             -- SMA 전용: 20|50|120|200
    threshold           numeric(10,2),                       -- AVG_PRICE: n(%, 음수 허용) / RSI: 0~100 / FEAR_GREED: 0~100 / SMA: NULL
    created_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trading.dca_execution (
    id           uuid NOT NULL PRIMARY KEY,
    strategy_id  uuid NOT NULL,                              -- FK → strategy.id
    trade_date   date NOT NULL,                              -- KST 거래일 (orders.trade_date와 동일 기준)
    status       varchar(20) NOT NULL,                       -- ORDERED | SKIPPED
    skip_reason  varchar(30),                                -- DcaSkipReason, SKIPPED일 때만
    order_id     uuid,                                       -- ORDERED일 때 orders.id
    quantity     integer,                                    -- 계획 수량 (축소 반영)
    reference_price numeric(12,2),                           -- 판정에 쓴 전일 종가 (데이터 실패 시 NULL)
    detail       varchar(500),                               -- 사람용 사유 상세 (예: "RSI 41.2 > 30")
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_dca_execution_strategy_trade_date UNIQUE (strategy_id, trade_date)
);
```

- 같은 거래일·같은 전략에 대해서는 `dca_execution` 한 행만 존재한다. 덕분에 배치 재개나 개장/마감 중복 진입 때 skip 알림과 이력이 두 번 생기지 않는다.
- WEEKLY는 `day_of_week`가 필수이고 `day_of_month`는 NULL이어야 한다. MONTHLY는 `day_of_week`가 NULL이어야 한다. 이 교차 검증은 서비스(`DcaConfig` 컴팩트 생성자)에서 하고, DB CHECK로도 함께 건다.
- 조건 행 검증
  - SMA: `sma_period`가 필수이고 `threshold`는 NULL이어야 한다.
  - RSI·FEAR_GREED: `threshold`가 0~100이어야 한다.
  - AVG_PRICE: `threshold`는 -100 초과여야 한다.
  - 같은 indicator가 여러 번 나와도 허용한다(예: RSI ≥ 20 AND RSI ≤ 40).
  - 조건은 전략당 최대 10개다.
  - 검증은 `DcaCondition` 컴팩트 생성자에서 하고, DB CHECK로도 함께 건다(단계 ② V7 마이그레이션).

### 3.3 도메인 타입

- `trading.domain.model.DcaConfig`(record): `direction, amountUsd, orderType, frequency, dayOfWeek, dayOfMonth, conditions`. 검증은 컴팩트 생성자에서 한다.
- `matching.domain.model.DcaCondition`(record): `indicator, operator, smaPeriod, threshold`
- 사이클: 전략 등록 시 `StrategyCycle.start(...)`로 영구 사이클 1개를 만든다.
  - `startAmount`는 0으로 둔다. DCA는 사이클 손익을 쓰지 않는다.
  - `endsCycleOnLiquidation()`은 false다. 보유 0주가 되어도 사이클을 닫지 않는다.
  - `cycleSeedType`은 NONE으로 고정한다.
- 설정 수정: `PUT /api/trading-cycles/{id}/dca-config`가 새 `strategy_version`과 DCA 상세 행을 만든다(VR `vr-config` 선례). 사이클은 그대로 유지한다.

### 3.4 계좌+티커 중복 규칙 완화

`validateUniqueTicker`를 다음 규칙으로 바꾼다.

- 신규 전략이 DCA가 아니면 기존 규칙을 그대로 적용한다(같은 계좌·티커에 전략이 하나라도 있으면 거부).
- 신규 전략이 DCA이면, 같은 계좌·티커의 기존 전략이 모두 DCA이고 그중 같은 방향이 없을 때만 허용한다.
- 결과적으로 계좌+티커당 최대 2개(매수 DCA 1개, 매도 DCA 1개)까지 등록된다.

UPRO와 QLD는 DCA 전용 티커라 다른 전략 타입과는 충돌하지 않는다. 이 규칙은 나중에 기존 티커가 DCA에 열리는 경우에 대비한 방어다.

## 4. 실행일 판정 (DcaSchedule)

### 4.1 위치

- **순수 계산**은 `matching.domain.strategy.DcaSchedule`(정적 메서드)에 둔다. 입력은 US 세션일(`LocalDate`)과 `IntPredicate`류의 `isUsSessionOpen(LocalDate)` 함수이며, 날짜 변환은 하지 않는다.
- **KST↔US 변환**은 `marketcalendar.application.port.output.MarketCalendarPort`에 메서드 2개를 추가해 맡긴다. 구현체는 이미 allowlist에 있는 `MarketCalendarPersistenceAdapter`라서 allowlist를 바꿀 필요가 없다.
  - `LocalDate usSessionDateOf(LocalDate kstTradeDate)`
  - `boolean isUsSessionOpen(LocalDate usSessionDate)` — 기존 `isMarketOpen`과 같은 데이터. 연도 데이터가 없으면 false.
  - `LocalDate kstTradeDateOf(LocalDate usSessionDate)` — 다음 실행일 응답용
- `MarketCalendarPort`는 marketcalendar 모듈 "port" NamedInterface로 trading에서 이미 쓰고 있다. 구현 시 공개 범위를 확인한다.

### 4.2 규칙 (US 세션일 `d` 기준, `d`는 개장일이어야 한다)

- **DAILY**: 항상 실행일이다.
- **WEEKLY(요일 w)**
  1. 기간 = `d`가 속한 ISO 주(월~일).
  2. 목표일 = 그 주의 요일 w.
  3. 실행일 = 목표일 이후(목표일 포함) 같은 주의 첫 개장일. 없으면 목표일 이전 같은 주의 마지막 개장일.
  4. `d == 실행일`이면 실행한다.
- **MONTHLY(일자 n 또는 말일)**
  1. 목표일 = `min(n, 그 달 말일)`. 말일 옵션이면 그 달 말일.
  2. 실행일 = 목표일 이후(목표일 포함) 같은 달의 첫 개장일. 없으면 목표일 이전 같은 달의 마지막 개장일.
- 주말과 휴장일은 모두 "개장일 아님"으로 처리한다.
- `nextExecutionDate(fromUsSession, config, isOpen)`: `fromUsSession`부터 하루씩 늘려가며 처음으로 실행일 조건을 만족하는 날을 찾는다. 상한은 400일이고, 넘으면 empty를 돌려준다(휴장 데이터 미존재 연도 대비).

### 4.3 배치와의 매핑

- 개장 배치(22:00 KST, `DstInfo.forOpenBatch`)와 마감 배치(04:30 KST)는 같은 US 세션에 대해 같은 KST `tradeDate`를 받는다. 따라서 판정 키는 `usSessionDateOf(tradeDate)` 하나다.
- MOO 전략은 개장 배치에서만, MOC 전략은 마감 배치에서만 계획한다. 판정 함수는 같다.
- 전략 `startDate`(사이클 시작일)보다 앞선 거래일에는 실행하지 않는다. 기존 시작예정일 규칙과 같다.

## 5. 회차 실행 흐름

### 5.1 기존 배치 통합

DCA는 별도 배치를 만들지 않는다. `CycleOrderStrategy` 구현체 `DcaCycleOrderStrategy`(matching)로 기존 `TradingService.executeBatch` 흐름에 들어간다.

- **슬롯과 leg**
  - leg는 `DCA_BUY` 또는 `DCA_SELL` 단일 concrete leg다. timing은 MOO면 `AT_OPEN`, MOC면 `AT_CLOSE`.
  - `canSkipOrderComputation`은 해당 timing에 같은 방향 leg가 이미 있으면 true를 반환한다. 기존 슬롯 로직(`TradingOrderSlots.excludeExisting`)이 배치 재개와 수동 중복 저장을 막아준다.
- **우선순위**: `allocationPriority()`는 200이다. 기존 전략(VR 0, INFINITE, PRIVACY 100대)보다 뒤에 둬서, 같은 계좌를 쓸 때 기존 전략 예산을 먼저 보장한다.
- **cap**: `needsCapCheck()`는 false다. 접수 직전 `BuyOrderPriceCapper` 재보정 대상이 아니다. DCA의 지정가는 계획 시점의 캡가로 고정한다.
- **리포트와 포지션**
  - 체결 리포트는 기존 `TradingReporter` 경로를 탄다.
  - `cycle_position`에는 브로커 실잔고 스냅샷(holdings, avgPrice, usdDeposit)을 기록한다. 내부 원장 누적이 아니다(5.4).
  - 리포트 마커 `trading_batch_report`는 기존과 같다.

### 5.2 계획 단계 (전략·거래일당 1회)

`trading.application.service.DcaRoundPlanner`(신규)가 `PlanContext`의 DCA 전용 입력 `DcaInputs`를 조립하고, `DcaCycleOrderStrategy.plan()`이 순수 판정을 한다.

1. **실행일 판정**: 실행일이 아니면 아무 것도 남기지 않고 종료한다(이력·알림 없음).
2. **이미 처리됨**: `dca_execution(strategy, tradeDate)` 행이 있으면 종료한다. 기존 주문이 있으면 슬롯 로직이 접수만 이어간다.
3. **데이터 확보**(티커별 배치 메모 캐시)
   - 일봉: 4.3의 US 세션일 `s`를 기준으로 `date < s`인 확정 봉만 쓴다. 전일 종가 = 그중 마지막 봉의 close.
   - 봉은 항상 250봉을 확보한다(SMA 최대 200, RSI 안정화 250). 마지막 봉이 직전 US 개장일이 아니면 실패로 본다(6.1 신선도 검증).
   - 평단가 조건이 있으면 브로커 실잔고(holdings, avgPrice)를 조회한다. SELL이면 판매가능수량을 조회한다(6단계).
   - FEAR_GREED 조건이 있으면 CNN 값을 조회한다(6.2).
   - 하나라도 실패하면 `SKIPPED(DATA_UNAVAILABLE)`.
4. **조건 평가**(AND)
   - 모든 조건의 지표값을 먼저 계산한다. 하나라도 계산할 수 없으면(봉 수 부족 등) 다른 조건의 참·거짓과 관계없이 `SKIPPED(DATA_UNAVAILABLE)`.
   - 그다음 입력 순서대로 평가한다. 하나라도 거짓이면 `SKIPPED(CONDITION_NOT_MET)`. `detail`에 첫 번째 미충족 조건과 실측값을 기록한다. 연산자는 뒤집어 표시한다.
     - 예: `RSI(14) 48.20 > 35`, `전일 종가 92.10 ≥ SMA(200) 88.40`, `전일 종가 90.00 > 평단 85.00 × (1 − 5%) = 80.75`
     - 수치는 소수 둘째 자리까지, 기준값은 trailing zero를 제거해 표시한다.
   - AVG_PRICE: `prevClose op avgPrice × (1 + n/100)`. holdings·avgPrice는 브로커 실잔고(`LiveBalancePort`)이고, AVG_PRICE 조건이 있을 때만 조회한다.
     - 보유 0주이고 BUY면 조건을 참으로 본다(첫 매수 허용).
     - 보유 0주이고 SELL이면 이 조건은 평가를 건너뛴다(참으로 취급). SELL은 6단계에서 NO_HOLDINGS로 끝나므로 사유 우선순위상 NO_HOLDINGS로 보고된다.
   - SMA: `prevClose op SMA(period)`
   - RSI: `RSI14 op threshold`
   - FEAR_GREED: `value op threshold`
5. **기본 수량**: `floor(amountUsd / prevClose)`. 0이면 `SKIPPED(BELOW_ONE_SHARE)`.
6. **잔고 반영**
   - SELL: 판매가능수량(`SellableQuantityPort`)에서 같은 계좌·티커·거래일의 기존 PLANNED/PLACED SELL 예약분을 뺀 값으로 상한을 둔다. 결과가 0이면 `SKIPPED(NO_HOLDINGS)`.
   - BUY: 계획 단계에서는 줄이지 않는다. 예산 축소는 allocator가 맡는다(5.3).
7. **주문 생성**: `PlannedOrder(ticker, tradeDate, orderType, timing, direction, leg, quantity, price)`
   - BUY: `price = PriceCapPolicy.capFor(prevClose)`(= prevClose × 1.05).
   - SELL: `price = prevClose`. 브로커 포맷 단계에서 시장가로 바뀐다(2.2, 5.5). allocator SELL 경로는 가격을 쓰지 않는다.

skip은 `plan()`의 반환이 아니라 `DcaRoundPlanner`가 결과 타입 `DcaRoundDecision(Planned | Skipped(reason, detail))`로 받아 처리한다. `CycleOrderStrategy.plan()`의 `Optional.empty()` 계약은 바꾸지 않는다. DCA 판정 로직은 `DcaCycleOrderStrategy.decide(DcaInputs)`라는 순수 메서드로 노출하고, `plan()`은 이 메서드를 감싸기만 한다.

### 5.3 예산 배정 (BUY 축소)

- `DcaCycleOrderStrategy.fitBuysToBudget(buyOrders, budget)`: 단일 BUY의 수량을 `floor(budget / price)`로 줄인다(price는 캡가).
  - 결과가 0이면 빈 목록을 돌려주고, allocator가 거절한다.
  - 기준이 캡가인 이유: 브로커는 지정가 × 수량으로 주문가능금액을 검증한다. 전일종가 기준으로 수량을 잡으면 APBK0988(주문가능금액 초과)이 난다.
  - 그 결과 예수금이 빠듯하면 요구사항의 "floor(예수금 / 기준가)"보다 최대 약 5% 적게 산다. spec 차원의 의도된 차이다.
- allocator가 BUY를 거절하면 `SKIPPED(INSUFFICIENT_BALANCE)`로 기록한다.
  - 현재 allocator 결과에서 거절된 사이클을 식별할 수 있는지 구현 시 확인한다.
  - 식별할 수 없으면 DCA 전략은 "PLANNED 저장 실패 = 거절"로 판정한다. 저장 직후 `dca_execution` 기록 단계에서 주문 존재 여부로 판정한다.
- 기존 `InsufficientBalanceEvent`(사용자 알림)는 DCA에서는 발행하지 않는다. `DcaRoundSkippedEvent`가 대신하므로 중복 알림을 막는다.

### 5.4 보유수량·평단 SSOT

DCA의 보유수량과 평단은 **브로커 실잔고(계좌+티커 합산)**가 SSOT다.

- 근거: 매도 DCA는 스스로 매수하지 않으므로, 전략별 내부 원장으로는 보유가 항상 0이다. 매수 DCA와 매도 DCA가 한 포지션을 공유하는 이상 원장을 둘로 쪼개면 둘 다 틀린 값이 된다.
- 결과
  - 사용자가 같은 계좌에서 수동으로 매매한 수량도 평단과 보유수량에 섞인다. UI에 "계좌 전체 보유 기준"으로 안내한다.
  - `cycle_position`의 DCA 행은 원장이 아니라 리포트 시점의 브로커 스냅샷이다. `StrategyDetail.currentHoldings`도 이 값을 쓴다.
  - DCA 스냅샷의 `usd_deposit`은 0으로 기록한다. 잔고검증(`calcFreeCash`)이 전략별 `usd_deposit`을 점유 시드로 합산하기 때문이다. 계좌 예수금 전체를 기록하면 같은 계좌의 다른 전략 등록이 막힌다.
  - 리포트는 자기 주문(externalOrderId) 체결만 사용한다. 같은 티커의 반대 방향 DCA 체결이 섞이지 않게 하기 위함이다.
  - 계좌 단위 통계·포트폴리오 집계에서 DCA 전략을 제외한다. 매수·매도 DCA가 같은 보유 스냅샷을 각자 기록해 이중으로 집계되기 때문이다.
  - 같은 계좌·티커의 매수 DCA와 매도 DCA는 같은 날 같은 스냅샷을 각자 기록한다.

### 5.5 브로커 매핑 (`OrderType × OrderDirection`)

| 브로커 | MOO SELL | MOO BUY | MOC SELL | MOC BUY |
|---|---|---|---|---|
| KIS | `31`, 가격 "0" | `32`(LOO) @캡가 | `33`, 가격 "0" | `34`(LOC) @캡가 |
| Toss | LIMIT DAY @0.01 (개장 후 접수) | LIMIT DAY @캡가 (개장 후 접수) | LIMIT CLS @0.01 (기존) | LIMIT CLS @캡가 |
| MOCK | 항상 체결 | 항상 체결 | 항상 체결(기존) | 항상 체결 |

- `KisOrderApi.resolveOrderDvsn`의 시그니처를 `(OrderType, OrderDirection)`으로 바꾼다. `KisResponseParser.formatPrice`도 방향을 받는다. 시장가 "0"은 매도 MOO/MOC에만 적용한다.
- `TossOrderApi.resolvePrice`: 0.01 치환은 **SELL에만** 적용한다. 지금은 BUY MOC에도 0.01이 들어가는 잠재 버그가 있다(현재는 BUY MOC 사용처가 없어 무증상).
- `stats.domain.backtest.FillSimulator`도 MOO를 항상 체결로 처리한다(enum 추가에 따른 컴파일 정합).

### 5.6 개장 배치 22:00 이동과 개장 전 접수

- `TradingOpenScheduler` cron을 `0 0 22 * * MON-FRI`로 바꾼다.
- `DstInfo`의 `OPEN_BATCH_FIRE_TIME`(22:30)을 22:00으로 바꾼다. 이 값은 `openBatchMissedFor` 판정에 쓰인다.
- `TradingBatchResumer`의 개장 재개 윈도우("월~금 22:30~")를 22:00으로 바꾼다.
- `scheduler-time-table.md`와 `workflow.md`의 개장 시각 서술을 갱신한다.
- 흐름
  - 계획·저장 직후, `waitUntilMarketOpen()` **전에** "개장 전 접수 대상"을 먼저 접수한다.
  - 대상은 KIS 계좌의 DCA `AT_OPEN` 주문(31/32)이다.
  - 기존 AT_OPEN 주문(INFINITE 매도, VR 사다리)과 Toss/MOCK의 DCA AT_OPEN은 지금처럼 개장까지 기다린 뒤 접수한다.
  - 판정은 `Broker.supportsOpenAuctionOrders()`(shared.kernel)로 한다. KIS만 true다. 정적 사실이고 소비처가 trading 한 곳이라 포트를 늘리지 않는다.
- 개장 전 접수 구간도 배치 임계구역(`TradingBatchRunState`)에 등록한다. 재기동 종료 처리와 재개 규칙은 기존 `PLACING` 단계를 그대로 쓴다.
- 영향
  - 기존 AT_OPEN 전략의 계획 시점이 30분(DST) 앞당겨진다.
  - 접수 직전 가격 재조회(`reloadPlacementPrices`)와 재캡은 그대로라 stale-cap 위험은 늘지 않는다.
  - 비DST에서는 대기가 60분에서 90분으로 늘어난다.

### 5.7 이력 기록과 알림

- `dca_execution` 기록 시점
  - SKIPPED: 계획 단계에서 판정 즉시 기록
  - ORDERED: PLANNED 저장 성공 직후 기록. `order_id`와 축소 반영된 수량을 남긴다.
  - 체결·미체결 결과는 이력 행에 복제하지 않는다. 조회 API가 `orders`를 조인해 내려준다(7.3).
- 이벤트 `trading.application.event.DcaRoundSkippedEvent(strategyId, tradeDate, reason, detail)`
  - SKIPPED를 기록하는 트랜잭션 안에서 발행한다.
  - `tradingcore.notify`의 `DcaSkipNotifier`가 `@TransactionalEventListener(AFTER_COMMIT)`으로 사용자 텔레그램/푸시에 보낸다. EPR 재발행 대상이다.
  - 같은 (strategy, tradeDate)는 UNIQUE라 한 번만 기록되므로 알림도 한 번만 나간다.
- 주문 성공 알림은 기존 체결 리포트(`TradingReportReadyEvent`)로 충분하다. 신규 알림은 만들지 않는다.
- `DATA_UNAVAILABLE`은 관리자에게도 알린다(`TradingErrorReportPort`). 데이터 피드 장애는 사용자 문제가 아니기 때문이다.

## 6. 데이터 소스

### 6.1 일봉과 지표

- **조회**: `CandlePort.getDailyCandlesBefore(symbol, beforeUsSession, count)`(단계 ①에서 추가됨)
  - Toss 구현은 200봉 단위로 페이지를 넘기며 조회한다. 다음 페이지의 `before`는 직전 페이지에서 가장 오래된 봉 날짜의 00:00 UTC다. 빈 페이지가 오거나 count를 채우면 멈추고, 날짜 기준으로 중복을 제거한다.
  - 반환은 `date < beforeUsSession`인 확정 봉만, 오름차순으로 준다.
  - Toss `adjusted` 파라미터는 true로 보낸다. 분할·배당 조정이 안 된 봉으로 지표를 계산하면 SMA가 왜곡된다. 이 메서드에만 적용한다(기존 호출 영향 없음). 실측은 구현 Task 0에서 한다.
  - DCA는 조건 유무와 관계없이 항상 250봉을 요청한다.
- **신선도 검증**: 마지막 봉 날짜가 `beforeUsSession` 직전 US 개장일(시장 캘린더로 역탐색)과 다르면, 피드 지연으로 보고 `DATA_UNAVAILABLE`로 처리한다. 오래된 종가로 수량·조건을 판정하지 않기 위함이다.
- **계산**: `matching.domain.model.DcaIndicators`(순수 정적 메서드)
  - `sma(closes, period)`: 마지막 period개 종가의 평균. 데이터가 period개 미만이면 empty.
  - `rsiWilder(closes, 14)`: Wilder 평활. 종가가 15개 미만이면 empty. 안정화를 위해 호출 측은 250봉을 넘긴다.
  - empty면 `DATA_UNAVAILABLE`.
- **캐시**: `DcaRoundPlanner`가 프로세스 메모리에 `(ticker, usSession) → 검증된 봉 목록`을 둔다.
  - 세션일 이전 확정 봉은 바뀌지 않으므로, 같은 세션의 개장·마감 배치와 여러 전략이 공유해도 안전하다.
  - 새 세션 키가 들어오면 다른 세션 키를 지운다. 따라서 맵 크기는 DCA 티커 수를 넘지 않는다.
  - 조회 실패나 신선도 검증 실패 결과는 캐시하지 않는다. 다음 전략·배치가 다시 조회한다.
  - 영속 캐시는 두지 않는다.
- 전일 종가도 같은 봉 데이터의 마지막 close를 쓴다. 조건 평가와 수량 계산이 같은 값을 보게 하기 위함이다. `BrokerPricePort.getPrevClose`는 쓰지 않는다. adjusted 시계열이라 배당락일에는 배당만큼 낮은 값이 되지만 허용한다.

### 6.2 CNN 공포탐욕지수 (trading-core 직접 조회)

- 포트 `trading.application.port.output.FearGreedIndexPort`: `OptionalInt valueBefore(LocalDate usSessionDate)`
- 어댑터 `trading.adapter.out.feargreed.CnnFearGreedIndexAdapter`
  - root `CnnFearGreedAdapter`와 같은 엔드포인트(`production.dataviz.cnn.io/index/fearandgreed/graphdata`)를 쓴다.
  - 응답의 `fear_and_greed_historical.data[]`(x = epoch ms, y = score)에서 US 동부 날짜가 `usSessionDate`보다 이전인 마지막 점을 고른다. 응답 필드 구조는 구현 Task에서 실측해 확정한다.
  - US 날짜 변환이 필요하다. 이 어댑터를 `UsTradeDates` allowlist에 추가하지 않고, epoch ms → `America/New_York` `LocalDate` 변환(`ZoneId` 직접 사용)으로 처리한다. `UsTradeDates`는 KST 거래일 ↔ US 거래일 변환용이라 해당이 없다.
- RestClient 빈 이름은 `fearGreedRestClient`로 하고, 타임아웃은 5초로 둔다.
- 실패(HTTP 오류, 파싱 실패, 해당 점 없음)하면 empty를 반환하고, 판정은 `DATA_UNAVAILABLE`이 된다.
- root `FearGreedScheduler`와 별개로 동작한다. 두 프로세스가 각자 하루 몇 번씩 호출하는 정도라 부하는 무시할 수 있다.
- 아키텍처 원장: 서로 다른 프로세스에 같은 외부 피드 어댑터가 하나씩 있는 구조다. 값 타입 복제가 아니므로(포트 반환은 `OptionalInt`) own-type 게이트 대상이 아니다.

## 7. API 계약 (kista-ui 회신 대상)

### 7.1 등록·수정 요청

- `POST /api/accounts/{accountId}/trading-cycles` 요청 DTO에 `dca` 객체를 추가한다. `type=DCA`일 때 필수이고, 그 외 타입에서는 무시한다.
- DCA일 때 무시하는 기존 필드: `divisionCount`, `cycleSeedType`, `initialUsdDeposit`, `initialHoldings`, `initialAvgPrice`, VR 필드. 보유와 평단은 브로커 실잔고를 쓰기 때문이다.
- `scheduledStartDate`는 DCA에도 적용한다.

```json
{
  "type": "DCA",
  "ticker": "UPRO",
  "scheduledStartDate": "2026-10-20",
  "dca": {
    "direction": "BUY",
    "amountUsd": 300.00,
    "orderType": "MOC",
    "frequency": "MONTHLY",
    "dayOfWeek": null,
    "dayOfMonth": null,
    "conditions": [
      { "indicator": "AVG_PRICE", "operator": "LTE", "threshold": -5.0 },
      { "indicator": "SMA", "operator": "LT", "smaPeriod": 200 },
      { "indicator": "RSI", "operator": "LTE", "threshold": 35 },
      { "indicator": "FEAR_GREED", "operator": "LTE", "threshold": 25 }
    ]
  }
}
```

- `dayOfMonth: null` + `frequency: MONTHLY` = 말일. 별도 플래그는 두지 않는다.
- `dayOfWeek`: `MONDAY`~`FRIDAY`(java `DayOfWeek` 이름). 주말은 거부한다.
- 설정 수정: `PUT /api/trading-cycles/{id}/dca-config`(본문은 위 `dca` 객체와 같음)
  - 티커와 방향은 바꿀 수 없다. 방향을 바꾸면 매수/매도 동시 등록 규칙을 우회하게 되므로, 다르게 보내면 400.
- 검증 실패는 400 ProblemDetail.
- 같은 계좌·티커·방향의 DCA가 이미 있으면 기존 종목 중복 등록과 같은 경로를 탄다. `IllegalStateException`은 공통 매핑에 따라 400("Invalid State")이 되고, `code`는 없다.
  - detail: "같은 계좌·종목에 같은 방향의 DCA 전략이 이미 있습니다."
  - 비DCA 중복의 기존 detail은 "이미 해당 종목으로 등록된 전략이 있습니다: {ticker}"이며 바꾸지 않는다.

### 7.2 조회 응답

`TradingCycleResponse`에 `dca` 객체를 추가한다. DCA가 아니면 null이다.

```json
"dca": {
  "direction": "BUY",
  "amountUsd": 300.00,
  "orderType": "MOC",
  "frequency": "MONTHLY",
  "dayOfWeek": null,
  "dayOfMonth": null,
  "conditions": [ ... ],
  "nextExecution": { "usSessionDate": "2026-10-30", "kstTradeDate": "2026-10-31" },
  "lastExecution": { "kstTradeDate": "2026-09-30", "status": "SKIPPED", "skipReason": "CONDITION_NOT_MET" }
}
```

- `nextExecution`: 오늘 이후(오늘 배치가 아직 안 돌았으면 오늘 포함) 첫 실행 예정일로, 보정 규칙이 반영된 값이다.
  - 계산 기준일은 `DstInfo.nextTradeDate()`. 해당 timing의 배치가 이미 지났으면 다음 날부터 센다.
  - 휴장 데이터가 없는 구간이면 null.
  - 조건 충족 여부와는 무관한 "판정 예정일"이다.
- `lastExecution`: 최근 `dca_execution` 1건. 없으면 null.
- `currentHoldings`: 브로커 스냅샷(5.4)

### 7.3 회차별 실행 이력

`GET /api/trading-cycles/{id}/dca-executions?page=0&size=20` (최신순, 소유권 검증)

```json
{
  "content": [
    {
      "kstTradeDate": "2026-09-30",
      "usSessionDate": "2026-09-29",
      "status": "ORDERED",
      "skipReason": null,
      "detail": null,
      "referencePrice": null,
      "quantity": 3,
      "order": { "id": "…", "status": "FILLED", "filledQuantity": 3, "filledPrice": 87.40 }
    },
    {
      "kstTradeDate": "2026-08-30",
      "status": "SKIPPED",
      "skipReason": "CONDITION_NOT_MET",
      "detail": "RSI(14) 48.20 > 35",
      "order": null
    }
  ],
  "page": 0, "size": 20, "totalElements": 2
}
```

- 실행일이 아니어서 판정하지 않은 날은 행이 없다.
- `referencePrice`: SKIPPED 행은 판정에 쓴 전일 종가(데이터 실패 시 null)를 담는다. ORDERED 행은 단계 ①에서 null이다.
- `nextExecution`의 기준일은 배치 규칙과 맞춰 사이클 시작일 다음 날 이후로 잡는다. 배치(`filterScheduledStart`)는 시작일 당일을 제외한다.

### 7.4 메타 API (`GET /api/meta`)

`StrategyCapability`에 필드 4개를 추가한다. 기존 필드는 유지한다. `StrategyTypeMeta`에는 `availableTickers`와 같은 레벨로 평탄하게 노출하며, 값은 code 문자열 배열이다. 기존 전략은 모두 `[]`.

| 필드 | DCA 값 |
|---|---|
| `orderTypes` | `["MOO","MOC"]` |
| `directions` | `["BUY","SELL"]` |
| `frequencies` | `["DAILY","WEEKLY","MONTHLY"]` |
| `conditionIndicators` | `["AVG_PRICE","SMA","RSI","FEAR_GREED"]` |

DCA의 `divisionCounts`는 `[]`, `supportsReverseMode`와 `requiresPrivacyBase`는 false다.

`conditionIndicators`는 서버가 등록을 허용하는 지표만 단계별로 노출한다. 단계 ②는 `["AVG_PRICE","SMA","RSI"]`이고, FEAR_GREED는 단계 ③에서 추가한다. 최상위 `dcaIndicators`는 처음부터 4개 전부를 노출한다.

`MetaBundle` 최상위에 키를 추가한다. 기존 키 뒤에 붙인다.

- `orderTypes: EnumMeta[]` — LOC, MOC, LIMIT, MOO 전체
- `orderDirections: EnumMeta[]` — BUY("매수"), SELL("매도")
- `dcaFrequencies: EnumMeta[]` — DAILY("매일"), WEEKLY("매주"), MONTHLY("매월")
- `dcaOperators: EnumMeta[]` — LTE("이하"), LT("미만"), GTE("이상"), GT("초과")
- `dcaSkipReasons: EnumMeta[]` — DATA_UNAVAILABLE("데이터 확보 실패"), CONDITION_NOT_MET("조건 미충족"), NO_HOLDINGS("보유 수량 없음"), INSUFFICIENT_BALANCE("예수금 부족"), BELOW_ONE_SHARE("1주 미만 금액")
- `dcaIndicators: DcaIndicatorMeta[]` — `{code, label, description, smaPeriods}`
  - `smaPeriods`는 SMA만 `[20,50,120,200]`이고, 나머지는 `[]`.
  - label: AVG_PRICE("평단가"), SMA("이동평균선"), RSI("RSI(14)"), FEAR_GREED("공포탐욕지수(CNN)")
  - `smaPeriods` 값의 SSOT는 `DcaIndicator.SMA_PERIODS` 상수(shared.kernel)로 두고, 등록 검증도 같은 상수를 쓴다.

EnumMeta label은 각 enum의 `label` 필드가 SSOT다. 위 한국어 값은 그 초기값이다.
- openapi는 `ErrorCodeOpenApiCustomizer`/springdoc 자동 반영이므로 UI는 `gen:types`로 받는다.

### 7.5 기존 엔드포인트의 DCA 처리

- `/execute`(수동 실행), 미리보기: DCA면 409. 신규 `ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED`.
  - detail: "DCA 전략은 바로 주문과 미리보기를 지원하지 않습니다."
  - 계좌 단위 미리보기(`previewBatch`)는 409로 막지 않고 DCA 전략만 결과에서 제외한다. 같은 계좌의 다른 전략 미리보기까지 막지 않기 위함이다.
- 브로커 정보는 `TradingCycleResponse`에 추가하지 않는다. `accountId`로 계좌 응답(`AccountResponse.broker`)을 참조한다.
- `/pause`, `/resume`, 삭제: 기존 그대로. 일시정지·삭제 시 미체결 주문 취소 경로도 기존 그대로다.

### 7.6 런타임 생성 정책 (runtime-config / 관리자 설정)

DCA는 기존 전략과 같은 런타임 정책 맵에 들어간다. UI 등록 폼은 지금처럼 `runtimeConfig.strategies[code].enabled === true`인 타입만 노출하면 된다.

- `TradingPolicySettings.strategies`에 `DCA` 키를 추가한다. 이 맵은 모든 StrategyType 키가 필수다.
- 기본값(`TradingPolicySettings.defaults()`)
  - `StrategyCreationSettings(enabled=false, ticker=field(customizable=true, allowed=[UPRO, QLD], default=UPRO), 나머지 필드 null)`
  - 저장 행에 DCA 키가 없으면 기존 "신규 enum 상수 백필" 경로로 이 기본값이 들어간다.
  - 기본이 비활성인 이유: 단계 ①(조건 미지원)이 배포되는 순간 사용자에게 노출되지 않도록 하기 위함이다. 운영자가 관리자 설정에서 켠다.
- 관리자 설정
  - `AdminSettingsRequest.StrategyRequest.toDomain`의 switch에 `case DCA -> new StrategyCreationSettings(enabled, value.tickerValue(), null, null, null, null)`을 추가한다.
  - 관리자 화면은 DCA 행의 enabled 토글과 ticker 필드만 편집한다. DCA 전용 필드(주기·금액 등)는 사용자 입력이라 런타임 정책 대상이 아니다.
- INFINITE 기본 ticker 허용값도 `List.of(StrategyTicker.values())`라서 그대로 두면 UPRO·QLD가 섞인다. INFINITE capability와 같은 명시 목록(MAGX, USD, TQQQ, SOXL)으로 바꾼다.
- 정책 저장 검증을 추가한다: 각 타입의 ticker `allowedValues`는 `StrategyType.capability().availableTickers()`의 부분집합이어야 한다. 관리자가 INFINITE에 UPRO를 허용하는 식의 설정을 저장 시점에 거부(400)한다.
- `DcaCreationResolver`(trading `domain/strategy`)는 ticker만 해석한다. 반환은 `ResolvedCreation(ticker, StrategyDefaults.DEFAULT_DIVISION_COUNT, null, null, null)`이고, 빈은 `CycleStrategyBeanConfig`에 등록한다.

## 8. 아키텍처 배치 요약

| 구성요소 | 모듈·패키지 |
|---|---|
| DcaFrequency/Indicator/Operator/SkipReason, OrderType.MOO, 티커, capability | `com.kista.shared.kernel` |
| DcaSchedule, DcaIndicators, DcaCondition, DcaCycleOrderStrategy | `com.kista.tradingcore.matching` ("kernel") |
| DcaConfig, DcaRoundPlanner, dca-config 수정 서비스, 이력 조회 유스케이스 | `com.kista.tradingcore.trading` |
| strategy_dca_version/condition, dca_execution 영속 어댑터 | `trading.adapter.out.persistence` |
| FearGreedIndexPort + CNN 어댑터 | `trading.application.port.output` / `trading.adapter.out.feargreed` |
| CandlePort 페이징 조회, KIS/Toss 매핑, supportsOpenAuctionOrders | `com.kista.tradingcore.broker` |
| MarketCalendarPort 변환 메서드 | `com.kista.tradingcore.marketcalendar` |
| DcaSkipNotifier | `com.kista.tradingcore.notify` |
| MetaController 필드 | root `com.kista.api.web` (shared.kernel 상수만 읽음, 프로세스 간 호출 없음) |

- 프로세스 경계를 넘는 신규 wire 타입은 없다. DCA API는 trading-core가 직접 서빙한다.
- 문서 동기화 대상: `flows.yml`(DCA 흐름, 개장 시각, skip 알림), `README.md`(전략 목록, 스케줄 시각), `scheduler-time-table.md`, `workflow.md`, `modules/tradingcore/{trading,matching,broker}.md`, `kis-api.md`(ORD_DVSN 31/32 추가).

## 9. 오류 처리 원칙

- 데이터 실패, 조건 미충족, 잔고 부족은 모두 **해당 전략·회차만** skip한다. 같은 배치의 다른 전략은 기존 격리 규칙(`TradingBatchGuard`)대로 계속 진행한다.
- 판정 단계에서 예상하지 못한 예외가 나면 기존 `TradingErrorEvent` 경로를 탄다. 이때 `dca_execution`은 기록하지 않으므로 같은 날 재개 시 다시 판정한다.
- skip 사유 우선순위: `DATA_UNAVAILABLE` → `CONDITION_NOT_MET` → `NO_HOLDINGS`(SELL) → `BELOW_ONE_SHARE` → `INSUFFICIENT_BALANCE`(allocator)

## 10. 테스트 전략

- **matching 순수 단위 테스트**
  - `DcaScheduleTest`: 월말 보정, 같은 주·월 내 이월, 기간 끝 휴장 시 직전 당김, 말일 휴장, 주 전체 휴장, 400일 상한
  - `DcaIndicatorsTest`: SMA와 Wilder RSI를 알려진 값과 대조
  - `DcaCycleOrderStrategyTest`: 조건 AND 평가, 보유 0 BUY/SELL 분기, BELOW_ONE_SHARE, 캡가, `fitBuysToBudget` 축소
- **trading**
  - `DcaRoundPlannerTest`: 데이터 실패 → DATA_UNAVAILABLE + 이벤트 발행, 이미 처리된 날 skip, SELL 판매가능수량 상한
  - `StrategyCreationServiceTest`: 매수·매도 DCA 동시 등록 허용, 같은 방향 중복 거부, 비DCA 기존 규칙 유지
- **broker**
  - `KisOrderApiTest`: 방향별 ORD_DVSN(31/32/33/34)과 가격 포맷
  - `TossOrderApiTest`: BUY는 캡가 유지, SELL만 0.01
- **persistence**: `dca_execution` UNIQUE 위반 시 중복 이벤트가 나가지 않는지
- **아키텍처**: INFINITE capability에 UPRO와 QLD가 포함되지 않는지 확인하는 단위 테스트, 기존 ArchUnit·Modulith 통과
- **스케줄러**: `DstInfo.openBatchMissedAt`의 22:00 경계, `TradingBatchResumer` 개장 윈도우 경계

## 11. 구현 단계 (권장 순서)

0. 확인 작업
   - KIS 공식 문서에서 미국 매수 ORD_DVSN 허용값과, 프리마켓 시간대 31/32 접수 가능 여부를 확인한다.
   - Toss 캔들 `adjusted` 파라미터와 CNN historical 응답 구조를 실측한다.
1. **무조건 DCA**: enum, 티커, capability 명시화, 스키마, 등록·수정, 중복 규칙 완화, DcaSchedule, 계획·배정·접수, 이력, skip 알림, 브로커 매핑, 개장 배치 22:00 이동, API·메타
2. **지표 조건**: AVG_PRICE, SMA, RSI, CandlePort 페이징·adjusted·신선도 검증, 조건 테이블(V7). FEAR_GREED는 계속 400이고 메타 `conditionIndicators`에서도 뺀다(7.4)
3. **공포탐욕지수**: CNN 어댑터, FEAR_GREED 조건

각 단계는 독립적으로 배포할 수 있다. 1단계만 배포된 상태에서 조건이 포함된 등록 요청이 오면, 미지원 indicator로 400을 반환한다.

## 12. 남은 위험

- **개장 전 KIS 장개시 주문 접수 시각**: 22:00 KST는 DST 기준 개장 30분 전이다. KIS가 이 시각에 31/32 주문을 받는지는 Task 0에서 확인한다. 받지 않으면 5.6을 다시 검토한다.
- **Toss MOO는 시가 체결을 보장하지 않는다**: 개장 경매 TIF가 없어 개장 직후 지정가로 근사한다. UI에 "Toss는 개장 직후 체결가 기준" 안내가 필요하다.
- **캡가 미체결**: 매수 LOO/LOC의 캡가가 전일종가 + 5%라, 갭상승이 5%를 넘으면 미체결된다. 3배 레버리지 ETF에서는 드물지만 발생할 수 있다. 미체결은 기존 리포트에서 CANCELLED로 기록되며 이월하지 않는다.
- **개장 전 접수의 예산 선점**: KIS DCA MOO 매수는 개장 전에 접수된다. 그래서 같은 계좌의 VR·INFINITE AT_OPEN 배정보다 먼저 live 예산을 쓴다. 이 경로에서는 DCA 우선순위 최하(200)가 지켜지지 않는다.
- **배포 순서**: 정책 맵이 모든 StrategyType 키를 필수로 요구하므로, kista-api와 kista-trading을 같은 커밋으로 동시 배포한다.
- **계좌 공유 평단**: 수동 매매분이 평단 조건에 섞인다(5.4). 사용자 안내로 대응한다.
