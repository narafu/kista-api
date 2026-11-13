# 매매·VR 공식 SSOT

이 파일의 공식은 단위 테스트로 고정돼 있다. 공식을 바꾸면 **이 문서와 테스트를 같은 작업에서 함께 고친다**(공식 변경은 동작 변경이다 — 리팩토링 커밋과 섞지 않는다). **자동 로드되지 않는다. 매매·VR 공식·VR 로직(`matching/`·`trading/`)을 건드리는 작업 전 반드시 Read한다.**

각 전략은 네 단으로 적는다.
- **문헌 규칙**: 원 저작자 규칙. 출처 위계는 원저작자 카페 글(수집 못 함) > 소유자 velog 글(`docs/reference/laor/vr-velog.md`) > quantstack 제3자 정리본(`docs/reference/quantstack/`). 무한매수법은 velog 글이 없어 quantstack이 기준이다.
- **KISTA 변형과 이유**: 문헌 규칙을 KISTA가 다르게 구현한 곳. 이유가 없으면 결함이다.
- **KISTA 고유 요소**: 문헌에 없는 실무 장치.
- **운영 기본값**(4절): 공식이 아니라 운영 판단으로 바뀔 수 있는 값.

문헌 대조 이력·판정 근거는 `docs/superpowers/fable-review/guides/08-formulas.md`.

## 1. 무한매수법 4.0 (INFINITE)

### 1-1. 문헌 규칙 (`docs/reference/quantstack/infinite-buying-v4.md`)
- 1회 매수금 = 잔금 ÷ (분할수 − T). T는 매수 체결 시 누적 가산, 쿼터매도 시 ×0.75.
- 별% = 목표수익률 × (1 − 2T/분할수). TQQQ 40분할이면 T마다 0.75%p씩 15% → −15%. 별지점 = 평단 × (1 + 별%), 소수 2자리.
- 전반전(T < 분할수/2): 1회분 절반은 평단 LOC, 절반은 별지점 LOC. 후반전: 1회분 전액 별지점 LOC.
- LOC 매수점 = 별지점 − 0.01, LOC 매도점 = 별지점(같은 종가에서 매수·매도가 함께 체결되지 않게 하는 장치). 평단 LOC 매수는 −0.01 없음.
- 매도: 보유 1/4 별지점 LOC + 나머지 3/4 지정가(평단 × (1+목표수익률)).
- 처음 매수(보유 0): 1회분 전액을 종가보다 10~15% 높은 가격(큰수) LOC 1건.
- T > 분할수 − 1이면 리버스모드. 4.0은 쿼터손절(1/4 MOC)을 없애고 리버스모드로 대체했다.
- 리버스: 첫날 보유 ÷ (분할수/2) MOC 매도. 이후 별지점(최근 5거래일 종가 평균) LOC 매도(보유 ÷ (분할수/2)) + 잔금/4 쿼터매수 LOC(별지점 − 0.01). 종가가 평단 × (1 − 목표수익률)보다 커지면 일반모드 복귀.
- 계산기 검증값(B3, TQQQ 40분할 평단 50): T=1 14.25%·57.13(매수 57.12), T=10 7.50%·53.75, T=20 0%·50.00, T=21 −0.75%·49.63, T=40 −15%·42.50. `InfinitePositionTest.starPoint_matchesReferenceCalculator`·`InfiniteStrategyTypeTest.locPrices_*`가 이 값으로 고정돼 있다.

### 1-2. 공식 (코드)
```
averagePrice = holdings==0 ? prevClosePrice(전일종가) : 평단  (평단 갱신은 scale=2 HALF_UP 단일 반올림 `AccountBalance.LEDGER_SCALE` — 4자리 중간값 금지: DB numeric(20,2) 재반올림과 이중 반올림이 생긴다)
purchaseAmount = averagePrice × holdings
totalAssets = deposit + purchaseAmount
unitAmount = totalAssets ÷ divisionCount  (금액 — scale=2 HALF_UP, 분모는 리터럴 20이 아닌 divisionCount)
currentRound(T) = holdings==0 ? 0 : purchaseAmount ÷ unitAmount  (비율 — 금액 자릿수로 반올림하지 않는다, 중간 scale=10, floor 없음)
priceOffsetRate(별%) = targetProfitRate × (1 − 2×T/divisionCount)  (비율 — 반올림하지 않는다)
referencePrice(별지점) = averagePrice × (1 + priceOffsetRate)  (가격 — scale=2 HALF_UP, 여기서만 반올림)
locBuyPrice = referencePrice − 0.01  (LOC 매수가)
targetPrice = averagePrice × (1 + targetProfitRate)  (scale=2 HALF_UP)
isFinalRound = unitAmount > deposit  또는  referencePrice > deposit
```
- `deposit` = 전략 원장 예수금(최신 `cycle_position.deposit` — `workflow-ledger-price.md` "전략별 원장 상한"). 브로커 잔고가 아니다.
- **전·후반 분기**: `T < divisionCount/2` → 전반, 그 외(경계 T = divisionCount/2 포함) → 후반.
- **첫 매수(holdings==0)**: LOC 매수 1건, 가격 = min(locBuyPrice, deposit), 수량 = max(1, floor(unitAmount ÷ 가격)), leg `INFINITE_FIRST_BUY`. 매도 없음. 보유 0이면 averagePrice = 전일종가라 locBuyPrice = 전일종가 × (1+targetProfitRate) − 0.01이 큰수 가격이다.
- **전반**: LOC 매수①(평단가, 수량 max(1, floor(unitAmount/2 ÷ 평단))) + LOC 매수②(locBuyPrice, 수량 = (unitAmount − 평단×매수①수량) ÷ locBuyPrice — 잔여 ≤ 0이면 없음, 아니면 최소 1주) + LOC 매도(holdings/4, referencePrice) + 지정가 매도(holdings − holdings/4, targetPrice, 개장 접수).
- **후반, isFinalRound 아님**: LOC 매수(locBuyPrice, 수량 max(1, floor(unitAmount ÷ locBuyPrice))) + LOC 매도 + 지정가 매도(전반과 같음).
- **후반, isFinalRound**: MOC 매도 1건(holdings ÷ (divisionCount/2), 최소 1주, 마감 접수, leg `INFINITE_MOC_SELL`)만. 리버스 플래그는 마감 리포트에서만 켜지므로 플래그가 꺼진 채 최종회차가 된 날(보유 有 등록·리버스 종료 직후·리포트 뒤 인출·종가 없는 리포트)에 탄다.
- isFinalRound(리버스 진입)에 isEarlyStage 가드가 필요 없는 이유: holdings ≥ 1·원장 ≥ 0이면 전반부에서는 성립하지 않는다(전반 ⇒ 원장 > 매입액 ≥ 평단, 기준가 ≤ 평단×(1+targetProfitRate)).

### 1-3. KISTA 변형과 이유
| 항목 | 문헌 | KISTA | 이유 |
|---|---|---|---|
| T | 체결마다 누적 가산 | 원장에서 매일 재계산(`purchaseAmount ÷ unitAmount`) | 상태를 저장하지 않아 어긋날 일이 없고 추가·인출·부분 체결이 자동 반영된다. 1회 매수금은 수식상 같다: `잔금 ÷ (n − R) = D ÷ (n − n·C/(D+C)) = (D+C)/n = unitAmount`(D=예수금, C=매입액). 매수에 의한 T 변화도 같고(리버스 쿼터매수 `R' = R + (n−R)/4`), 매도는 실현 손익만큼 분모가 달라져 문헌의 ×0.75와 근사로만 같다 |
| 전·후반 경계 T = n/2 | 본문·계산기 표는 전반, 사이트 주문 카드는 후반(문헌 불명확 E-1) | 후반 | 별지점 = 평단이라 가격 효과는 같고 수량 배분만 다르다. 사이트 주문 카드와 같게 둔다 |
| 첫 매수 큰수 | 종가 +10~15%(사이트 코드 +13%) | 전일종가 × (1+목표수익률) − 0.01을 접수 전 cap(현재가 × 1.05)이 낮춘다. 가격은 원장 예수금을 넘지 않는다 | 큰수는 증권사 가격 제한을 피하는 실무 장치라 비율은 규칙이 아니다. 원장 상한은 최소 1주 보장 수량이 원장을 넘어 매수 전체가 거절되는 교착을 막는다(1주 가드는 전일종가 기준) |
| 일반모드 최종회차 | (리버스로 넘어감) | 리버스 첫날 규칙 MOC를 일반모드가 낸다, 최소 1주 | 리버스 플래그가 체결 뒤 리포트에서만 켜지는 구조 때문. 다음 날 첫날 MOC가 한 번 더 나갈 수 있다(`fable-review/questions.md` Q-04) |
| 전반 절반 배분의 자투리 | 본문 불명확(사이트는 별지점 먼저) | 평단 쪽 먼저, 최소 1주 | 문헌이 정하지 않은 부분 |
| 1/4 수량 | 정수 처리 불명확 | floor | |

### 1-4. KISTA 고유 요소
- **최소 1주 보장**: 매수①·매수②(잔여 > 0일 때)·후반 매수·첫 매수·최종회차 MOC.
- **리버스 진입 추가 조건** `referencePrice > deposit`: 기준가로 1주도 못 사면 진입. 진입 `unitAmount > deposit`은 문헌 `T > n − 1`과 같다(`(D+C)/n > D ⟺ n·C/(D+C) > n − 1`).
- **리버스 예산 소진**: 쿼터매수로 1주도 못 사면 같은 수량을 MOC 매도(`REVERSE_INFINITE_QUOTA_MOC_SELL`). 손실 확정을 앞당긴다.
- **리버스 종료 `≥`**: 종가 ≥ round2(평단 × (1 − targetProfitRate)), holdings > 0, 체결 후 평단 기준(`ReverseModePosition.shouldExit`). 문헌은 "보다 커지면".
- **보정 주문·가격 cap**: 아래 1-5·1-6.

### 1-5. 보정 주문 (INFINITE 가격 캡 경로)
```
BUY 가격이 cap(currentPrice × 1.05, PriceCapPolicy)을 넘으면 cap 기준으로 첫 매수(보유 0, 단일 LOC — base 중 가장 비싼 주문 기준)/전반(①②)/후반(단일 LOC)으로 나눠 재산정한 뒤 보정 3건을 붙인다
보정 i(1~3) = LOC 매수 1주, 가격 = unitAmount ÷ (누적수량 + 1)  (scale=2, FLOOR — (누적수량+1)×가격 ≤ unitAmount), leg INFINITE_CORRECTION_01~03, 누적수량 0이면 생략
```
- 전반 재산정: cappedAvg = 첫 base 가격.min(cap), cappedRef = (매수② 가격, 매수②가 없던 날은 locBuyPrice).min(cap). 동가면 q1+q2 병합(`INFINITE_EARLY_MERGED_BUY`), 분리 시 q2 > 0일 때만 매수② 추가.
- cap과 무관하게 일반모드 BUY마다 `buildOrders`가 보정 3건을 부착한다(isFinalRound·리버스모드 제외, base BUY 바로 뒤·SELL 앞).
- 보정 합계가 원장 예수금을 넘으면 뒤쪽 보정부터 제거(`trimCorrectionsToBudget`). base는 줄이지 않는다.

### 1-6. 가격 cap 배수 1.05 경위
- cap은 원래 `currentPrice × 1.10`이었고 PRIVACY에도 같은 cap을 적용한다(Toss CLS 경매에서 현재가와 차이가 큰 지정가가 가격 범위 초과로 취소되는 것을 막기 위함). 1.05는 **정량 근거 없는 운영 판단값**이다(값은 4절).
- 2026-10-25 재평가는 "보정 leg가 붙은 날 = cap 발동일"로 표본(USD 36일)을 골랐는데, 보정은 cap과 무관하게 매일 붙으므로 이 식별법은 무효다. 그 표본에서 가장 비싼 base 매수가 미체결된 날이 1일뿐이었다는 관찰만 참고로 남는다. 재평가는 식별법을 새로 정한 뒤 다시 한다(백로그 — `fable-review/findings/05-frozen-decisions.md` P-04).

### 1-7. 리버스모드 별지점
```
starPointPrice = 현재 사이클에서 INFINITE 상세(cycle_position_infinite)가 있는 최신 cycle_position 5건(STAR_POINT_WINDOW — 마감 리포트·등록 개장 행. 수동 체결 보정 행은 체결가가 종가 자리에 들어가므로 제외. 로테이션 개장 행은 detail이 없어 제외되나 새 사이클은 보유 0에서 시작해 리버스 진입 전에 창이 리포트 행으로 채워져 영향 없음)의 closingPrice 평균  (scale=2, HALF_UP — null·0 이하 종가 제외, 남는 값이 없으면 null)
LOC 매도 가격 = starPointPrice, 쿼터매수 LOC 가격 = starPointPrice − 0.01(TICK_SIZE)
```
- 문헌은 "5거래일", KISTA는 "저장 행 5건". 보통 같고, 사이클 초반에는 5건 미만 평균이 된다.
- 쿼터매수는 접수 전 가격 캡 대상이다: 주문가 = min(starPointPrice − 0.01, 현재가 × 1.05), 수량 = floor(round2(deposit/4) ÷ 주문가) — 상세 `workflow-capper.md`.
- 리버스모드 첫날은 별지점 없이 MOC 매도만 낸다 — 별지점은 2일차부터 계산(`CycleOrderComputer.computeStarPointPrice`).
- 백테스트(`BacktestEngine.STAR_POINT_WINDOW`)도 같은 5개 윈도우 — 두 값은 함께 바꾼다.
- 최신 N건 조회는 Pageable이 건수를 정하는 파생 쿼리로 한다. `findTopN…` 같은 숫자 없는 `Top`/`First`는 PartTree가 1건으로 고정해 별지점이 종가 1개로 계산된 적이 있다(`RepositoryLimitingQueryTest`가 재발 차단).

## 2. VR (밸류 리밸런싱)

### 2-1. 문헌 규칙 — 실력공식 (`docs/reference/laor/vr-velog.md` 3절)
KISTA는 VR 5.0 기본공식(`V' = V + P/G + C`, quantstack 정리본)이 아니라 소유자 velog 글의 **실력공식**을 쓴다(2026-11-12 사용자 결정, 지침서 08 F7). 두 식의 차이는 `(평가금 − V)/(2√G)` 항 하나다. 글에는 이 항의 유도·적용 조건이 없다(velog 대조 U2).

### 2-2. 공식 (코드)
```
lowerBand = V × (1 − bandWidth/100)  (scale=2, HALF_UP)
upperBand = V × (1 + bandWidth/100)  (scale=2, HALF_UP)
buyPrice(m)  = lowerBand ÷ (holdings + m − 1)  (scale=2, HALF_UP, m=1..20, divisor<1이면 skip)
sellPrice(s) = upperBand ÷ (holdings − s + 1)  (scale=2, HALF_UP, s=1..20)

V' = V + pool/G + recurringAmount + (평가금 − V) / (2√G)  (scale=2 HALF_UP, 중간 scale=10 — VrPosition.nextValue)
     평가금 = holdings × 종가
```
- **gradient(G)·poolLimitRate 램프**: 전략 최초 사이클 startDate부터 경과한 주수(weeks)에 따라 바뀐다. 값·기본값은 4절.
  - `gradientAt(weeks)`: `weeks < gGraceWeeks` → `initialGradient`; 이후 `gStepWeeks`마다 `+1`(고정, `StrategyVrDetail.G_STEP`), `gMax` 상한
    - `gStepWeeks=0`은 gradient 램프 자체를 비활성화(항상 `initialGradient` 유지) — 이때 `gMax`·`gGraceWeeks`는 무관해지므로 0 허용(등록·재설정 양쪽 `gStepWeeks > 0`일 때만 `gMax >= initialGradient` 강제)
  - `poolLimitRateAt(weeks)`: `weeks < pGraceWeeks` → `initialPoolLimitRate`; 이후 `pStepWeeks`마다 `-5%p`(고정, `StrategyVrDetail.POOL_LIMIT_STEP`), `poolLimitFloor` 하한(scale=2 HALF_UP)
    - `pStepWeeks=0`은 poolLimitRate 램프 자체를 비활성화(항상 `initialPoolLimitRate` 유지) — 이때 `poolLimitFloor`·`pGraceWeeks`는 무관해지므로 검증 없이 0 허용(등록·재설정 양쪽 `pStepWeeks > 0`일 때만 `0 < poolLimitFloor <= initialPoolLimitRate` 강제)
  - G·poolLimitRate 두 램프의 유예·단계주기는 서로 독립
  - weeks 재계산 시점: 사이클 롤오버(`VrCycleRolloverService`) 및 운영 중 재설정(`VrReconfigureService`) — 둘 다 `ChronoUnit.WEEKS.between(전략 최초 사이클.startDate, today)`(재설정은 음수면 0으로 보정 — 시작 전 재설정). 사이클 진행 중엔 `strategy_cycle_vr` 스냅샷(gradient·poolLimitRate) 고정
- **`strategy_cycle_vr.pool_limit_rate`**(비율, 달러 아님)를 스냅샷 저장. poolLimit(달러)은 저장하지 않고 조회 시점에 개장 `CyclePosition.deposit × poolLimitRate`로 파생 — 첫 사이클은 `poolLimitRateAt(0)`, 롤오버·재설정 사이클은 `poolLimitRateAt(weeks)`를 저장
- rollover due 조건: `cycle.startDate() + intervalWeeks ≤ today` (당일 포함)
- V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 관리자·사용자 알림. V=0·holdings=0인 채로 롤오버가 진행되는 경우도 `nextValue()` 결과를 그대로 쓴다(예전 존재했던 "V 강제 0 유지" 가드는 폐기됨) — `pool/G+recurringAmount` 항으로 다음 사이클 V가 자연 성장하고, 실제 매수는 항상 pool/poolLimit 실측 잔고 한도 내에서만 이뤄지므로 과다지출 위험이 없다

### 2-3. KISTA 고유 요소
- **주문 사다리**: 문헌은 "목적지는 V"까지만 말하고 주문표 산식이 없다. KISTA는 위 buyPrice/sellPrice로 1주씩 계단을 만든다(2주치 예약 주문 운용). 동일 가격 연속 rung은 수량 병합(매수), 매도는 holdings>20이면 마지막 단(s=20)에 잔여 전량.
- **G·poolLimitRate 자동 램프**: 문헌의 "1년 단위 점진 조정" 권고를 자동화한 것.
- **bootstrap 진입 판정(`VrStrategy.buildOrders`/`needsBootstrap`)**: `firstCycle` 개념 없이 순수 상태 기반으로 게이팅. holdings=0인데 V=0이면 사다리 공식 자체가 무의미(lowerBand=0)해 bootstrap 대상. holdings=0이고 V>0이어도 사다리 첫 유효 단(m=2, 가격=lowerBand 그대로)이 잔여예산을 초과하면 마찬가지로 bootstrap 대상 — `nextValue()` 공식이 holdings와 무관하게 매 롤오버 V를 키우므로(`pool/G+recurringAmount` 항), holdings=0이 지속되면 V가 예산 대비 과도하게 커져 사다리로는 영원히 매수가 불가능해질 수 있기 때문
  - **holdings>0 드리프트 케이스**: 등록 시 V=시장가×수량으로 확립되지만, 이후 holdings가 늘지 않는 채로 롤오버가 반복되면(`nextValue()`가 holdings 무관하게 V를 계속 키움) 사다리 첫 유효 단(m=1, divisor=holdings)조차 잔여예산을 초과할 수 있다. 이 경우도 매수만 bootstrap(예산 내 캡 가격 LOC)으로 대체하고, 매도 사다리는 이 드리프트와 무관하게 정상 생성한다(전량 bootstrap 전환과 달리 매도까지 사라지지 않음)
- **bootstrap 잔여예산(`remainingBudget`)**: 원칙은 `poolLimit − poolUsed`(이번 사이클에 이미 매수 체결된 금액 차감). 단 `poolLimit`이 0(사이클 개장 시점 예수금 자체가 0이었던 완전 무일푼 시작이라 poolLimit이 그 사이클 내내 영구 고정)이면 DB상 예수금(`pool`, `cycle_position` 최신 스냅샷)을 그대로 상한으로 대신 쓴다. 어느 쪽이든 DB상 예수금은 넘지 않는다(`governanceLimit.min(pool)`). `poolUsed`가 실제 체결 기준이라 부분/미체결 여부와 무관하게 다음날 정확한 잔여예산이 재계산된다. 예산<=0이면 빈 주문(다음 롤오버에서 `nextValue()` 공식이 V를 자연 성장시킴 — 실제로 holdings가 생기면 이 판정 자체가 꺼지므로 별도 처리 불필요)
- bootstrap LOC 가격: `PriceCapPolicy.capFor(referencePrice)`(= referencePrice × 1.05, currentPrice 없으면 전일종가로 대체 가능) — 일반 매수 캡과 동일 기준 사용. 주문 수량은 잔여예산/가격 내림 정수
- 가격 캡: `buyPrice > currentPrice × 1.05`(`PriceCapPolicy`, INFINITE/PRIVACY의 `BuyOrderPriceCapper`와 공용) 이면 cap 가격으로 교체 — scale=2 HALF_UP (currentPrice=null이면 미적용). VR은 매수 사다리 생성 시점(`VrStrategy.buildBuyOrders`)에는 캡을 적용하지 않고, 접수 직전 `BuyOrderPriceCapper`가 `VrCycleOrderStrategy.capBuyOrders()`를 거쳐 `VrStrategy.buildCappedBuyOrders()`로 재산정한다 — INFINITE/PRIVACY와 동일한 공통 보정 경로(전략별 다형성 디스패치는 `CycleOrderStrategy.capBuyOrders()`)
- 등록 검증: `initialValue`, `initialDeposit`, `recurringAmount` null은 0으로 취급
  - 적립식(`recurringAmount > 0`): 초기 V와 초기 시드가 모두 0이어도 등록 가능
  - 거치식/인출식(`recurringAmount <= 0`): `initialValue + initialDeposit > 0` 필수
  - 인출식(`recurringAmount < 0`): `initialValue + initialDeposit >= abs(recurringAmount) × 100 × (4 / intervalWeeks)` 필수 — 운영 중 재설정으로 `recurringAmount`를 인출식으로 바꾸는 경우도 `VrReconfigureService`가 동일 규칙 재검증
- **개장 금액 계약**: `initialDeposit` = 사이클 개장 USD pool(개장 `CyclePosition.deposit`), `startAmount` = 개장 예수금 + 개장 보유분 시장가, `poolLimit` = 개장 pool × `poolLimitRate` (scale=2 HALF_UP). 보유분 시장가를 pool에 포함하지 않는다.
- **종료 금액 계약**: VR 롤오버 `endAmount` = 마감 예수금 + 보유분 종가 평가액(scale=2 HALF_UP). 시작 후 재설정(강제 롤오버)은 이전 사이클을 자본 조정 전 포지션의 총자산으로 종료하고 새 사이클을 자본 조정 후 총자산으로 시작해 주입/인출을 이전 사이클 손익에 포함하지 않는다.
- **운영 중 재설정** (`PUT /api/trading-cycles/{id}/vr-config`, `VrReconfigureUseCase`/`VrReconfigureService`): 밴드폭·주기·적립금·램프 파라미터 수정 + 선택적 투자금 추가/인출(수량/예수금)을 "새 `strategy_vr_version` 발급 + 강제 롤오버(현재 사이클 종료→새 사이클 즉시 생성)" 단일 메커니즘으로 처리. VR 전용, 소유권 검증 필수
  - 시작 전 재설정(`!currentCycle.hasStartedBy(nextTradeDate)`, `StrategyCycle.hasStartedBy` — 배치·미리보기와 같은 술어)은 새 사이클을 만들지 않고 첫 사이클을 in-place 갱신한다(`CycleSnapshotCreator.reconfigureVrCycleBeforeStart` — 버전 교체·`startAmount`·최신 포지션(`updateBalance`)·RESET 이력(차액이 있을 때)·사이클 VR 상세, startDate 유지, 램프 weeks는 최초 사이클 기준(음수면 0), 새 사이클 시작 알림 없음). 시작 후 재설정은 강제 롤오버이며 옛 사이클 종료일 = 새 사이클 시작일 = 오늘(KST, nextTradeDate 아님 — 하루 겹침이 stats 연속성 판정을 깬다)
  - 램프 시계(경과주수)는 재설정해도 리셋하지 않음 — 항상 전략 최초 사이클 startDate 기준
  - 순수 파라미터 수정: V·holdings·deposit 이월. 수량 주입 +N주(단가 Pc): `holdings+=N`, `avgPrice` 가중평균, `V+=N×현재가`. 수량 인출 -N주: holdings·V 감소, 잔여 평단가 유지. 예수금 주입/인출은 deposit만 증감하고 V는 불변
  - 검증 순서: 램프 파라미터·투자금 추가 형태(수량 음수 금지 등)·인출식 최소자산 재검증까지 모두 통과한 뒤에만 브로커 미체결 주문 취소(`OrderCancelService`, 별도 트랜잭션이라 이후 실패해도 롤백 불가)를 호출 — 검증 실패 시 브로커에 실주문 취소가 나가지 않도록 순서 고정

## 3. PRIVACY (KISTA 고유 — 문헌 없음)
- **배수(자산비)** (`PrivacyStrategy.multiple` — 운영 주문 계획·미리보기·백테스트 공용): 배수 = `ourAsset ÷ fidaAsset`, 소수 2자리 FLOOR. `ourAsset` = 예수금 + 보유 × P, `fidaAsset` = (FIDA `current_cycle_start` + `current_cycle_realized_pnl` − FIDA 보유 × FIDA 평단) + FIDA 보유 × P. P는 전일 종가(최신 `cycle_position.closing_price`, 없으면 브로커 전일종가)다. FIDA 보유가 0이고 평단이 null이면 보유 항은 0이다. 무효(`PrivacyValuationException`)는 `fidaAsset ≤ 0`, `ourAsset ≤ 0`, FIDA 보유 > 0인데 평단 null, P가 필요한데 null일 때다 — 배치는 해당 전략만 격리하고 관리자에게 알리며, 미리보기는 `PRIVACY_BASE_BLOCKED`, 백테스트는 경고만 남기고 그 세션 주문을 건너뛴다. BUY 가감·SELL cap·버림 보정은 이 배수를 입력으로 쓸 뿐 공식은 그대로다. 추가 투입·인출은 최신 행 예수금을 바꾸므로 다음 계산 배수에 자동 반영된다. 예시(2026-11-11 운영 검증): P 56.20, 우리 자산 33814.11, FIDA 자산 661.13 + 6294.40 = 6955.53 → 배수 4.86, 계획 BUY 11주 기준 가감 후 BUY 45주. 2026-11-11 사용자 승인으로 고정 배수(start_amount ÷ currentCycleStart)에서 자산비 배수로 변경했다.

## 4. 운영 기본값 (공식 아님 — 운영 판단으로 바뀔 수 있다)
값을 바꾸는 것은 공식 변경이 아니다. 기존 전략은 등록 시점 값이 저장돼 있어 영향받지 않는다(가격 cap 제외).

| 값 | 현재 | SSOT |
|---|---|---|
| 무한매수 분할수 | 20/30/40, 기본 20 | `StrategyDefaults.DEFAULT_DIVISION_COUNT`, `StrategyType.capability()` `divisionCounts`, RuntimeSettings |
| 종목별 목표수익률 | `StrategyTicker.getTargetProfitRate()` | `StrategyTicker` |
| 매수 가격 cap 배수 | 1.05(1-6 경위) | `PriceCapPolicy.CAP_MULTIPLIER` |
| 무한매수 재등록 최소금액 계수 | 현재가 × 분할수 × 2 | `InfiniteCycleOrderStrategy.MIN_DEPOSIT_FACTOR` |
| VR 램프 | 아래 표 | `VrRampInput.withDefaults()`/`VrRampValidator` |

VR 램프 초기값·램프 파라미터 8개는 전략 등록 시 사용자 입력(`StrategyVrDetail`: `initialGradient/gGraceWeeks/gStepWeeks/gMax/initialPoolLimitRate/pGraceWeeks/pStepWeeks/poolLimitFloor`)이고, 생략 시 recurringMode(적립/거치/인출) 표(kista-ui `RAMP_DEFAULTS_BY_MODE`와 동기화) + 유예 52주·단계 26주로 채운다. 등록(`StrategyCreationService`)·재설정(`VrReconfigureService`, 검증만)·백테스트(tradingcore.stats `BacktestService`/`BacktestEngine`)가 공유한다.

| recurringMode | initialGradient | gMax | initialPoolLimitRate | poolLimitFloor |
|---|---|---|---|---|
| 적립(`recurringAmount>0`) | 10 | 20 | 1.0 | 0.5 |
| 거치(`recurringAmount==0`) | 10 | 20 | 0.75 | 0.5 |
| 인출(`recurringAmount<0`) | 40 | 50 | 0.1 | 0.1 |

소유자 velog 글의 값과 8건 다르다(인출 시작 G 20, 거치 pool 한도 90% 등 — 표는 지침서 08 3절). 2026-11-12 사용자가 코드 값이 맞다고 확인했다.

## 5. 통계 원금 (매매 공식 아님)
Stats는 `startAmount`가 아니라 `strategy_cycle.open_amount`(개장 시가 평가 — 개장 예수금 + 개장 보유분 시장가, DCA는 초기 보유 원가)에 추가·인출 이력을 더해 원금으로 쓴다(CQ-67). 매매 계산은 `open_amount`를 읽지 않는다. 개장 시점엔 두 값이 같고, 추가·인출은 `startAmount`만 바꾼다. 시드 수정·VR 시작 전 재설정도 `startAmount`만 바꾸고 `open_amount`는 그대로 두며, 원장 차액을 `cycle_capital_event` RESET으로 남긴다(CQ-69 — 원금 이중 계상 방지. VR 시작 전 재설정의 원금 차액은 원가 기준: 예수금 ± 주식 추가 수량 × 입력 단가 − 인출 수량 × 평단). 기존 행은 trading V17이 첫 스냅샷 종가로 백필했다(종가가 없거나 보유 0이면 `start_amount − 이력 합계`).
