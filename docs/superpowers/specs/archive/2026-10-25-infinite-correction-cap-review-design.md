# INFINITE 보정 주문·cap 점검 결정 설계

출처: `2026-10-24-strategy-own-ledger-budget-design.md` §9 "범위 밖 후속"의 INFINITE 보정·cap 4건 + 추가 점검 2건.
매매 공식(`docs/agents/modules/tradingcore/trading-formulas.md`)은 변경 금지 SSOT이며, 아래 변경은 사용자가 2026-10-25에 승인했다.

## 1. 배경과 재현 요약

재현은 컴파일된 `InfiniteStrategy`·`InfinitePosition`을 직접 호출해 얻었다(SOXL, targetProfitRate 0.20, 20분할).

| # | 현상 | 재현 |
|---|------|------|
| 1 | 보정 주문(`INFINITE_CORRECTION_01~03`)이 cap 발동 시에만 붙는다 | 발동 경계는 "평단 −5%"가 아니라 `현재가 < max(BUY 지정가)/1.05`라 회차마다 이동한다. 0회차(전일종가 50, ref 60)는 현재가 57.14 미만이면 발동 — 전일종가 대비 +14%라 사실상 매일 붙는다. 10회차(ref=평단 50)에서만 −4.8%. 15회차(ref 45)는 −14%. 10회차 예: 현재가 47.70(미발동)·종가 46.27이면 20주/925달러, 47.60(발동)이면 21주/≈970~1000달러 |
| 2 | 최소 1주 보장으로 회차 금액(unitAmount) 초과 | 서버 안내 최소 시드(가격×분할×2)에서 0회차 unitAmount=2p인데 base는 `p×1 + 1.2p×1 = 2.2p`. 시드 2000·가격 60이면 unitAmount 100, base 123. 원장이 `avg+ref` 미만이면 base가 원장을 넘어 BUY가 매일 전부 거절되고, holdings=0이면 매도도 리버스 진입도 없어 영구 교착(시드 100·가격 60 등) |
| 3 | 보정 가격 HALF_UP으로 `(N+1)×가격 > unitAmount` | unitAmount 1000, N=17 → 55.56×18 = 1000.08. FLOOR면 55.55×18 = 999.90 |
| 4 | cap 배수 1.05 근거 미기록 | 커밋·문서에 근거 없음(아래 §2.4) |
| 5 | 전반부 + `isFinalRound` 동시 성립 시 리버스 진입 | holdings ≥ 1·원장 ≥ 0에서 수학적으로 불가능(전반 ⇒ 원장 > 매입액 ≥ 평단, 기준가 ≤ 평단×1.2). Java 무작위 대입 전반 표본 18만 건 중 holdings>0 동시 성립 0건. 운영 DB의 INFINITE 음수 원장 0건(PRIVACY 과거 행 1건만) |
| 6 | SSOT 문서 ↔ 코드 불일치: 전반 매수② 공식 | 문서 `(unitAmount − avg×q1) × (1+priceOffsetRate) / referencePrice`, 코드 `earlyBuyQuantity2`는 계수 없음. 5c37c199f(2026-06-12 18:55)가 코드에서 계수 제거, 16177bd09(19:11)가 문서에 `(1+priceOffsetRate)`를 기입해 이후 계속 불일치. `BuyOrderPriceCapper.java:31` 주석에도 `(1+r)` 잔존 |

## 2. 결정 (사용자 승인)

### 2.1 보정 주문을 cap과 무관하게 항상 붙인다 (#1)
- 근거: 보정 주문 도입 커밋 c9cf79ede의 목적이 "장마감 가격 하락 대비"(종가 하락 시 회차 금액 미달 매수 보완)이고, 이는 cap 발동과 논리적으로 무관하다. 경계에서 매수가 계단식으로 바뀌는 현상이 사라진다.
- 적용 범위: INFINITE 일반모드에서 BUY가 나가는 모든 경우 — 전반(매수①② 또는 병합)·후반 LOC 매수. `isFinalRound`(MOC 매도만) 분기와 리버스모드(`ReverseInfiniteStrategy`)는 BUY 구성이 달라 대상 아님.
- 보정 공식은 기존과 동일: base BUY 다음에 3회, 각 회차 가격 = `unitAmount / (누적수량 + 1)`, 수량 1주, LOC, leg `INFINITE_CORRECTION_01~03`. 누적수량 0이면 skip.
- 원장 처리: `buildOrders`도 `buildCappedBuyOrders`와 같이 보정 추가 후 `trimCorrectionsToBudget(buys, position.usdDeposit())`로 뒤쪽 보정부터 생략한다(base 불변). `StrategyLedgerBudget.fit` → `fitBuysToBudget`(=같은 trim) 경로는 그대로다.
- cap 경로: `buildCappedBuyOrders`는 이미 입력에서 correction leg를 걸러 base만 재산정하고 보정을 다시 붙이므로 동작 변경 없다. 보정 가격은 base 가격 이하라, base가 cap 이하이면 보정도 cap 이하다(cap 판정 트리거 변화 없음).
- 감수하는 결과: 매일 BUY 예약 총액(지정가×수량 합)이 unitAmount 대비 약 10~14% 늘고 LOC 주문이 3건 더 나간다. 실제 체결액은 설계상 unitAmount 근처다. 원장 잔여가 unitAmount의 1.0~1.14배인 구간(대략 18~19회차)에서는 체결 가능했을 보정이 잘린다 — 기존 cap 발동일과 같은 성질이라 수용.

### 2.2 매수② 최소 1주는 매수①이 unitAmount를 다 쓰지 않았을 때만 (#2)
- `earlyBuyQuantity2(unitAmount, price1, quantity, price2)`: 잔여 `unitAmount − price1×quantity`가 0 이하이면 0, 아니면 `max(1, floor(잔여 / price2))`.
- 매수②가 0주면 그 주문을 만들지 않는다 — `buildEarlyStageOrders`와 `computeEarlyBuys` 모두. `computeEarlyBuys`의 병합 분기(cappedAvg == cappedRef)는 `q1+q2`로 그대로 동작한다.
- cap 재산정 경로 분기: 현재 `buildCappedBuyOrders`는 base BUY 개수(1건=후반, 2건=전반)로 전후반을 가른다. 매수② 0주인 전반 날은 base가 1건이라 후반 공식으로 잘못 들어가므로, 분기를 `position.isEarlyStage()`로 바꾸고 전반 재산정은 base가 1건이어도 `cappedRef = min(referencePrice, cap)`으로 매수②를 다시 판정한다(설계자 결정 — builder check 지적 반영). 회귀 테스트: "전반 매수② 0주 + cap 발동".
- 매수①(`earlyBuyQuantity1`)의 최소 1주와 후반 `lateBuyQuantity` 최소 1주는 유지한다(후반은 `isFinalRound`의 `referencePrice > usdDeposit` 판정이 원장 초과를 막는다).
- 효과: 시드 100·가격 60(unitAmount 5) 교착 예시는 base 60 ≤ 원장 100이 되어 매수가 진행된다. 0회차 교착은 원장이 1주 가격보다 작을 때만 남는다(진짜 매수 불가 — 이번 범위 밖).
- 주의: 잔여가 양수면 매수②는 여전히 최소 1주다(예: unitAmount 2p, q1=1 → 잔여 p > 0 → q2 = max(1, floor(p/1.2p)=0) = 1). 즉 최소 시드 0회차 base는 2.2p로 그대로다. 초과 상한이 "매수② 1주 이내"로 묶이는 것이 이번 변경의 범위이며, 매수①이 unitAmount 이상을 쓴 경우(소액 시드)에만 매수②가 사라진다.

### 2.3 보정 가격 반올림 HALF_UP → FLOOR (#3)
- `addCorrectionOrder`의 `unitAmount.divide(N+1, 2, HALF_UP)` → `FLOOR`. 다른 수량 계산(FLOOR)과 일관되고 `(N+1)×가격 ≤ unitAmount`가 보장된다.
- 사전 측정: 이 한 줄만 바꾸면 `InfiniteStrategyTypeTest`의 `buildCappedBuyOrders` 6건만 실패하고 `InfinitePositionTest`·`BacktestEngineTest`·`BuyOrderPriceCapperTest`·`TradingServiceTest`·`StrategyLedgerBudgetTest`·`ManualTradingServiceTest`는 통과했다.

### 2.4 cap 배수 1.05 — 값 유지, 결정 경위만 문서화 (#4)
- 값은 바꾸지 않는다.
- 문서화할 사실(추정 근거를 덧붙이지 않는다):
  - cap은 2026-06 이전부터 `currentPrice × 1.10`이었고, 4154c0482(2026-06-24)가 같은 cap을 PRIVACY에도 적용했다. 그 사유는 "Toss CLS 경매에서 현재가와 차이가 큰 지정가가 가격 범위 초과로 취소됨"이다.
  - d40d07e13(2026-07-28)에서 1.10 → 1.05로 줄였다. 사용자 진술: 특별한 정량 근거는 없고, 10%면 놓칠까 싶어 5%로 줄인 운영 판단값이다.
- 2026-10-25 재평가(운영 DB 읽기 전용 조회): cap이 발동해 보정이 붙은 INFINITE 일(day) 중 종가가 기록된 KIS 실계좌 36일(USD, 2026-07-17~10-24)에서 가장 비싼 base 매수(=cap 가격)가 미체결된 날은 1일(2026-08-04, 접수 시점 대비 종가 +8.2%)뿐이다. 나머지 35일의 "접수 시점 추정 현재가(cap÷1.05) → 종가" 변동은 최대 +1.8%였다. cap 배수 k에서 cap 발동일 체결액은 대략 unitAmount/k(1.05 ≈ 95%, 1.10 ≈ 91%)이고, 종가가 접수 시점(마감 30분 전) 현재가×k를 넘으면 그 다리는 미체결이다.
- 결론: 1.05 유지. 1.10은 측정된 이득이 위 1일뿐이고 매 발동일 체결액이 약 4%p 줄어든다. 1.02~1.03은 표본(36일·1종목·비교적 잔잔한 구간)에서 추가 미체결이 없었지만, 이득이 회차 금액의 약 3%p에 그치는 반면 3배 레버리지 ETF의 마감 30분 급등 꼬리를 표본이 대표하지 못한다. 표본이 쌓이면 같은 조회로 재평가한다(조회 SQL을 문서에 남긴다).
- 위치: `trading-formulas.md`(또는 `PriceCapPolicy` 한 줄 주석) — builder 재량, 단 SSOT는 문서 한 곳.

### 2.5 isEarlyStage 가드 추가 안 함 (#5)
- 코드 변경 없음. `trading-formulas.md`의 isFinalRound 설명에 "holdings ≥ 1·원장 ≥ 0이면 전반부에서는 성립하지 않는다(전반 ⇒ 원장 > 매입액 ≥ 평단 ≥ 기준가/1.2)" 한 줄만 남긴다.

### 2.6 SSOT 문서를 코드에 맞춘다 (#6)
- `trading-formulas.md` 전반 매수② 공식에서 `×(1+priceOffsetRate)` 제거 → `(unitAmount − averagePrice×매수①수량)/referencePrice`, 그리고 §2.2의 0주 규칙 반영.
- `BuyOrderPriceCapper.java:31` 주석의 `(unitAmount-averagePrice·q1)·(1+r)/referencePrice`를 같은 식으로 정정.

## 3. 목표 / 비목표 / 반목표
- 목표: 위 6건을 코드·SSOT 문서에 반영하고, 기대값이 바뀌는 테스트를 하나씩 근거와 함께 보고한다.
- 비목표: cap 배수 값 변경, 백테스트 기반 배수 재산정, 원장이 1주 미만인 0회차 사이클의 처리, 최소 시드 서버 강제, 매수①·후반 최소 1주 규칙 변경, 리버스모드 공식.
- 반목표: base 주문(매수①②·후반 LOC)의 가격·수량을 원장 맞춤으로 축소하는 것 — 공식 이탈. 보정 생략만 축소 수단이다.

## 4. 영향 범위
- 코드: `InfiniteStrategy`(buildOrders에 보정 추가+trim, buy② 0주 skip, FLOOR), `InfinitePosition.earlyBuyQuantity2`, `BuyOrderPriceCapper` 주석.
- `InfiniteCycleOrderStrategy.canSkipOrderComputation`: 현재 `correctionComplete`는 보정 3건 + (`EARLY_AVG`&&`EARLY_REF` | `MERGED` | `LATE_REF`)를 요구한다. 매수② 0주인 날은 `EARLY_REF`가 없어 complete로 판정되지 않고 재계산되지만, `excludeExisting`이 기존 leg를 걸러 중복 저장은 없다. 이 동작(재계산 허용)을 유지할지, `EARLY_AVG`+보정 3건도 complete로 볼지는 builder 재량 — 단 부분 저장 복구가 막히지 않아야 한다(testing.md "complete는 buildOrders 미호출, partial은 호출" 규칙).
- 백테스트: `BacktestEngine`은 커널 `buildOrders`를 그대로 쓰므로 보정 상시 부착·FLOOR·buy② 0주가 자동 반영된다. 백테스트 예산 계약(BUY 지정가 합계가 예수금 초과면 그날 BUY 전부 거절)은 `buildOrders` 내부 trim 덕에 보정 때문에 새로 거절되는 날이 생기지 않아야 한다.
- 문서: `trading-formulas.md`(전반 매수②·보정 주문·cap 경위·isFinalRound 한 줄), `docs/agents/workflow.md` "BuyOrderPriceCapper 보정 주문" 절(보정이 cap 경로 전용이 아니게 됨, HALF_UP → FLOOR), `docs/agents/testing.md`의 INFINITE 관련 서술 중 사실이 바뀌는 부분, 원 spec §9 항목에 처리 결과 링크. `flows.yml`은 보정 주문을 서술하지 않아 변경 없음(구현 시 재확인). kista-ui는 correction leg를 참조하지 않아 영향 없음.

## 5. 테스트·보고 요구
- 공식이 바뀌므로 `InfinitePositionTest`·`InfiniteStrategyTypeTest`·`BacktestEngineTest`에서 기대값이 바뀌는 단언을 테스트 이름별로 "이전 값 → 새 값, 바뀐 이유(§2.x)"로 보고한다. 바뀌지 않은 클래스도 "변화 없음"으로 명시한다.
- 새 회귀 테스트(최소):
  - cap 미발동 상황에서도 보정 3건이 붙는다(전반·후반 각 1).
  - 보정 가격이 FLOOR라 `(N+1)×가격 ≤ unitAmount`.
  - 매수①이 unitAmount 이상을 쓰면 매수②가 생성되지 않는다(예: 원장 100, 가격 60, 0회차 → BUY `60×1` + 보정).
  - 원장이 base+보정을 못 담으면 `buildOrders`가 뒤쪽 보정부터 뺀다.
- 고친 테스트가 회귀를 잡는지 변이 확인(수정을 잠시 되돌려 실패 확인)한다.
- 전체 스위트는 마지막 1회.

## 6. builder 재량
- 보정 추가 로직을 `buildOrders`·`buildCappedBuyOrders`가 공유하는 방식(헬퍼 추출 등).
- `canSkipOrderComputation` 처리(§4 조건 내).
- cap 경위 문서화 위치(§2.4 조건 내).
