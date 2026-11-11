# 08. 매매 공식 대조 (quantstack 정리본 vs KISTA 문서·코드)

- 상태: 작성 중(절 단위로 추가). 읽기 전용 대조이며 옳고 그름 판단은 하지 않는다. 실행·테스트는 하지 않았다(수치는 손계산/코드 판독).
- 약어: LIT = `docs/reference/quantstack/` (제3자 해설, 최종 기준 아님), DOC = `docs/agents/modules/tradingcore/trading-formulas.md`(FORM)·`workflow-capper.md`, CODE = `trading-core/src/main/java/com/kista/tradingcore/`.
- 코드 경로 약칭: INFPOS=`matching/domain/model/InfinitePosition.java`, INFSTR=`matching/domain/strategy/InfiniteStrategy.java`, REVPOS=`.../model/ReverseModePosition.java`, REVSTR=`.../strategy/ReverseInfiniteStrategy.java`, INFCYC=`.../strategy/InfiniteCycleOrderStrategy.java`, VRPOS=`.../model/VrPosition.java`, VRSTR=`.../strategy/VrStrategy.java`.
- 핵심 구조 차이(먼저): LIT V4.0은 T를 "누적 가산 상태값"으로 쓰고 1회매수금=잔금/(n−T). KISTA는 T를 저장하지 않고 매일 원장에서 `currentRound = 매입액/unitAmount`(V2.2/V3.0 방식 T)로 계산하며 `unitAmount = (예수금+매입액)/n`. 이 둘은 T=currentRound일 때 수식상 같은 값이 된다(잔금/(n−T) = 총자산/n, 손 유도). 차이는 T의 "변화 방식"(체결·매도 시)에서 생긴다.

## 1. INFINITE 4.0 일반모드 대조표

| # | 항목 | LIT (파일:절) | KISTA 문서 | KISTA 코드 (실제 식) | 판정 | 차이 설명 |
|---|---|---|---|---|---|---|
| 1 | 분할 수 | v4 §A1: 20/30/40 선택. 별% 공식은 20·40만 명시, 30은 불명확(§E5) | FORM: 허용 20/30/40, 기본 20 | `StrategyType.java`(INFINITE capability) `List.of(20,30,40)`; `StrategyDefaults.DEFAULT_DIVISION_COUNT=20`; `TradingPolicySettings.defaults()` INFINITE ticker 기본 SOXL·분할 기본 20 | 일치(30분할은 LIT 불명확) | 30분할 별% 식은 KISTA가 일반식(15−1.5T·20−2T 형태가 n에 비례)으로 확장 적용. LIT 사이트 코드도 같은 일반식(§C) |
| 2 | 종목·익절률 | v4 §A1: TQQQ/SOXL만 오피셜, 익절 +15%/+20% | FORM: targetProfitRate | `StrategyTicker.java:15-18` TQQQ .15, SOXL .20, **MAGX .15, USD .20**도 INFINITE 허용(`StrategyType`) | 차이(KISTA 확장) | MAGX/USD는 LIT에 없음(오피셜 외). 신규 기본 ticker는 SOXL(LIT은 초심자 TQQQ 권장 서술, 규칙 아님) |
| 3 | 1회 매수금 | v4 §A4: 잔금/(n−T) (매일 변동). 예 2만$·40분할 500→500.56 | FORM: `unitAmount = totalAssets ÷ divisionCount`, `totalAssets = deposit + averagePrice×holdings` | INFPOS:43-50 `unitAmount = (deposit + avg×holdings)/n`, scale2 HALF_UP. 0회차는 avg=prevClose(holdings=0이라 매입액 0) → deposit/n | 차이(구조) / 수식상 등가 | 위 "핵심 구조 차이". 단 KISTA의 매입액은 **평단×보유**(원가)이므로 실현이익은 예수금으로 들어가 unitAmount가 즉시 커진다(전액 복리). LIT는 "잔금/(n−T)"에서 T가 ×0.75 되는 방식으로 반영 → 매도 직후 값이 다를 수 있음(수치 비교 미수행) |
| 4 | T 정의 | v4 §A2: T는 누적 가산(1회분 +1, 절반 +0.5, 쿼터매도 ×0.75, 지정가매도 후 LOC매수 시 ×0.25+…, 미체결 불변). V3.0 방식(매입액/1회매수액)은 순환 문제로 폐기 | FORM: `currentRound = purchaseAmount ÷ unitAmount` (double, 소수 허용, floor 없음) | INFPOS:52-55 `purchaseAmount.divide(unitAmount, **scale 2 HALF_UP**).doubleValue()`; 보유 0이면 0.0 | 차이 | ① T는 저장·가산이 아니라 매일 재계산(V3.0식). ② 코드는 currentRound를 **소수 둘째 자리로 반올림**(LIT V4는 "자릿수 제한 없음", V2.2는 셋째 자리, V3.0은 둘째 자리 올림 — §E9). DOC는 "double, 소수점 허용"이라 쓰고 scale 2 반올림은 언급하지 않음(6절) |
| 5 | T 증분(부분 체결) | v4 §A2: 1회분 +1·절반 +0.5만. 부분 체결 처리 불명확(§E2). 사이트 코드: 금액 기준 0.5 단위 반올림(§C) | (없음 — T 가산 개념 없음) | 체결 평단·수량이 그대로 매입액에 반영 → 연속값. 0.5 단위 반올림 없음 | 차이(LIT 불명확) | KISTA는 체결금액 비율이 그대로 R에 반영(478/500이면 0.956에 해당). LIT 사이트는 +1로 반올림 |
| 6 | T 매도 시 변화 | v4 §A2: 쿼터매도 ×0.75, 지정가 ×0.25(+LOC매수 가산). 같은 날 둘 다 체결 처리 불명확(§E3) | (없음) | 매도로 보유 감소→매입액 감소(평단 불변), 예수금 증가→총자산 증가. R' = n·0.75·P/(D'+0.75P) 형태 | 차이(근사적으로 유사) | R'는 T×0.75보다 실현이익만큼 작아짐(손 유도). 전량 매도 시 보유 0 → R=0 (LIT: 보유 0=사이클 종료, T=0) |
| 7 | 별% | v4 §A3·§B2: TQQQ 20분할 15−1.5T, 40분할 15−0.75T; SOXL 20분할 20−2T, 40분할 20−T (일반식 15−(30/n)T, 20−(40/n)T). T=n에서 −15%/−20% | FORM: `priceOffsetRate = targetProfitRate × (1 − 2×currentRound/divisionCount)` scale2 HALF_UP | INFPOS:67-73 (double `roundFactor = 2.0*R/n`, BigDecimal.valueOf → multiply → scale 2) | 일치(식 동형) | r(1−2R/n) = r − (2r/n)R 이므로 LIT 일반식과 항등(r=.15/.20). **별%는 소수 둘째 자리(비율 단위 0.01=1%p)로 반올림**: priceOffsetRate는 비율값이라 scale=2는 1%p 단위 — 즉 별%가 정수 %로 반올림된다. LIT 계산기는 별%를 반올림하지 않고 별지점만 round2(§B2). **이 항목은 7절 상위 후보**(단위 테스트가 이 반올림을 고정: `InfinitePositionTest.java:52` 0.20×(1−2×1.33/20)=0.1734→0.17, 경계 테스트 :187-192) |
| 8 | 별지점 | v4 §A3: 평단×(1+별%), 별지점=매도점 | FORM: `referencePrice = avg×(1+priceOffsetRate)` scale2 | INFPOS:75-78 | 일치(반올림 차이는 #7) | 용어 referencePrice |
| 9 | 매수점/매도점 0.01 | v4 §A3: 매수=별지점−0.01, 매도=별지점 | FORM: 후반 LOC매수 가격=referencePrice, LOC 매도=referencePrice+0.01 | INFSTR:187 `locSellPrice = referencePrice + TICK_SIZE`; 후반 매수 `position.referencePrice()`(INFSTR:174) | 차이 | 간격 0.01은 보존하나 **기준이 이동**: LIT 매수=별−0.01/매도=별, KISTA 매수=별/매도=별+0.01. KISTA 매수가 +0.01 유리하게 체결(LOC 매수는 종가≤가격 체결), 매도 문턱도 +0.01 높음 |
| 10 | 전/후반 경계 | v4 §A4-1·§E1: 전반 T<n/2, 후반 ≥n/2 / 본문 "절반 초과→후반" / 계산기 T=n/2는 전반 / 주문카드 후반 — 문헌 내부 모순 | FORM: `priceOffsetRate>0`이면 전반, ≤0이면 후반. 반올림으로 경계 직전(SOXL 20분할 ≥9.76, TQQQ ≥9.67)이 후반 | INFPOS:88-90 `priceOffsetRate().compareTo(ZERO) > 0` | 차이 | KISTA는 T 비교가 아니라 **반올림된 별%의 부호**로 분기. T=n/2에서 별%=0 → 후반(LIT 사이트 주문카드와 같음, 계산기와 다름). 경계 직전은 #7의 정수% 반올림 때문에 T<n/2에서도 후반(DOC가 명시) — LIT에 없는 동작 |
| 11 | 전반전 매수 구성 | v4 §A4-1: 1회매수액 절반 별지점 LOC + 절반 평단 LOC + 아래 단계 추가. 사이트 코드(§C): 별지점 floor((R/2)/buyPx) + 평단 floor((R−별매수액)/평단) | FORM 전반: 매수①(unit/2/평단, 평단가, 0이어도 최소 1주) + 매수②((unit−평단×수량①)/기준가, 기준가) | INFSTR:142-158, INFPOS:117-129 ①=max(1,floor(unit/2/avg)) @avg ②=remaining≤0이면 0, 아니면 max(1, floor(rem/ref)) @ref | 차이(순서·최소1주) | LIT(§C 사이트 코드)는 별지점 쪽을 절반으로 먼저, 평단 쪽이 자투리를 흡수. KISTA는 **평단 쪽이 절반(고정), 별지점 쪽이 자투리 흡수**(반대). 또 KISTA는 최소 1주 강제(unit<가격이어도 1주 → 1회매수액 초과 가능). LIT 별지점 매수가는 −0.01, KISTA는 −0.01 없음(#9). ①②가 같은 가격(동가)이면 병합 leg |
| 12 | 후반전 매수 | v4 §A4-1: 1회매수액 전체를 별지점 LOC. §C: floor(R/buyPx) | FORM: unitAmount≤deposit 그리고 referencePrice≤deposit이면 LOC 매수(unit/ref, ref) | INFPOS:131-134 `max(1, floor(unit/ref))`; INFSTR:170-175 | 차이(미세) | 최소 1주 강제는 FORM이 후반에는 명시하지 않음(6절). 별지점−0.01 없음(#9) |
| 13 | 아래 단계 추가 LOC 매수 | v4 §A4-1 "아래로 LOC매수 추가"(가격식 본문에 없음, §E6). 사이트 코드(§C): 가격 round2(R/(V+k)), V=기존 수량, k=1…, 최대 10단계, 기준가보다 낮을 때만 | FORM "보정 주문": 보정 3건=LOC 1주, 가격=unitAmount÷(누적수량+1) FLOOR, leg INFINITE_CORRECTION_01~03. workflow-capper.md는 분자를 "K"로 표기 | INFSTR:24, 129-140 `unitAmount.divide(totalQuantity+1, 2, FLOOR)`, `withCorrections` 3회, 매 회 누적수량에 이전 보정 1주가 합산 | 일치(구조)/차이(세부) | 식 형태는 LIT 사이트 코드와 동일(R/(V+k)). 차이: 3단계(LIT 10), FLOOR(LIT round2), "기준가보다 낮을 때만" 검사 없음, 예수금 초과 시 뒤쪽 제거(`trimCorrectionsToBudget`). 본문 규칙에는 가격식이 없으므로 불명확 영역. 보정은 isFinalRound·리버스 제외, cap 무관하게 상시 부착 |
| 14 | 매도 구성 | v4 §A4-2: 보유 1/4 별지점 LOC 매도, 3/4 지정가 +15%/+20% (프리장 시작에 접수) | FORM: LOC 매도(holdings/4, referencePrice+0.01), 지정가(holdings−holdings/4, targetPrice) | INFPOS:149-165 `holdings/4`(정수 내림), `holdings − quarter`; targetPrice=avg×(1+r) scale2; INFSTR:183-198 지정가는 AT_OPEN 선접수, LOC 매도는 마감 배치(AT_CLOSE) | 일치(+0.01 차이는 #9) | 1/4 반올림 규칙은 LIT 불명확(§E4), KISTA는 내림. 보유 3주 이하면 quarter=0이라 LOC 매도 없음, 전량이 지정가로. LIT 사이트 코드도 floor |
| 15 | 접수 시점 | v4 §A4-2: 지정가는 프리장 시작(17/18시 KST), LOC는 본장 중 언제든. guide-loc-orders: LOC 접수 마감 장 마감 10~20분 전 | workflow-batch.md: AT_OPEN(지정가 매도)은 개장 배치 선접수, AT_CLOSE(LOC 매수·매도)는 마감 배치(04:30 KST, DST 장마감 30분 전)에 매수와 **같은 position으로** 접수, 접수 직전 현재가로 cap 재보정 | INFSTR:183-198(timing), `BuyOrderPriceCapper` | 차이(운영 방식) | LIT에는 "마감 30분 전 현재가를 보고 가격을 바꾸는" 단계 자체가 없다(#cap, 4절) |
| 16 | 첫 매수(T=0) | v4 §A4-1: 무조건 1회 매수 의도, 전일종가 +10~15% LOC(큰수), 그 아래로 LOC 추가. 사이트 코드: 종가×1.13 큰수, floor(R/종가)주 | FORM: averagePrice는 holdings==0이면 prevClosePrice | INFPOS:34-37 avg=prevClose, R=0 → offset=r → 전반 분기: ①=floor(unit/2/prevClose)@prevClose, ②=잔여/ (prevClose×(1+r))@prevClose×(1+r) | 차이 | KISTA 첫날은 별도 로직 없이 일반 전반 공식: 절반은 전일종가, 절반은 +15%(TQQQ)/+20%(SOXL). LIT는 전액을 +10~15% 큰수에(범위 초과: SOXL +20%). 단 실주문은 cap(현재가×1.05)이 위 가격을 대부분 깎음(4절). 큰수의 존재 이유(증권사 ±20% 제한 우회)와 반대로 KISTA는 cap으로 ±5% 이내로 묶음 |
| 17 | 사이클 종료·재시작 | v4 §A5: 보유 0 → 종료, 지정가 후 LOC매수 되면 종료 아님. 재시작 복리/단리 자유 | `CycleOrderStrategy.endsCycleOnLiquidation()` 기본 true(INFINITE 종료) | 사이클 종료 후 재시작(rotation) 시 시작 금액 정책은 별도(trading.md) — 본 대조에서 코드 미판독 | 불확실(미판독) | 재시작 금액(복리/단리)은 `strategy_cycle.start_amount`·CycleRotationService 쪽. 읽지 못함 → 7절 아님 |
| 18 | 반올림·호가 | v4 §B2: round2 = Math.round(x*100)/100 (HALF_UP), 정수 주만 | FORM: scale2 HALF_UP, 수량 FLOOR | INFPOS 전반 HALF_UP, 수량 FLOOR(최소1주 예외) | 일치 | 매수가는 FLOOR 아님(HALF_UP). 보정가는 FLOOR |
| 19 | 최소 시드 | (없음) | INFCYC:36,148-152 `minRequiredDeposit = price × (n × 2.0)` | | KISTA 고유 | 4절 |

> 검증 벡터 하나(손계산): TQQQ 40분할, 평단 50, 별% LIT T=1 → 14.25%(별지점 57.13). KISTA: R=1.0일 때 priceOffsetRate = .15×(1−2×1/40)=.1425 → **setScale(2, HALF_UP)=0.14** → referencePrice = 50×1.14 = 57.00 (LIT 57.13, 차이 0.13/주). `Currency.USD.amountScale()`=2 라는 가정(`constraints.md` USD scale 규칙) 하에서 코드 판독. 테스트(`InfinitePositionTest.java:52`)가 1%p 단위 반올림을 기대값으로 고정하고 있어 의도된 동작으로 보이나, LIT 계산기와는 직접 충돌 → 7절 1순위.

## 2. INFINITE 리버스모드 대조표

| # | 항목 | LIT | KISTA 문서 | KISTA 코드 | 판정 | 차이 설명 |
|---|---|---|---|---|---|---|
| R1 | 진입 조건 | v4 §A6: 원금 소진 시 20분할 T>19, 40분할 T>39 (§E8: 일반모드 "T>n−1"과 표기만 다름) | FORM: 후반 `unitAmount>deposit 또는 referencePrice>deposit`이면 isFinalRound=리버스 진입 | INFPOS:93-95 isFinalRound; INFPOS:100-112 `nextReverseMode`: 일반모드+holdings>0+prevClosePrice≠null+isFinalRound. 체결 후(마감 리포트 시점, `CyclePositionPersistor.java:~92-111`) 판정 | 일치 + 차이 | `unitAmount>deposit` ⟺ R>n−1 (손 유도: (D+P)/n>D ⟺ P>(n−1)D ⟺ n·P/(D+P)>n−1) → LIT와 수식상 동일. **추가 조건 `referencePrice>deposit`(기준가 1주도 못 삼)은 LIT에 없음**(R≤n−1이어도 진입 가능). 진입은 "체결 후 상태"로 판정되고 다음 거래일부터 리버스 적용 |
| R2 | 진입 당일(첫날) 매도 | §A6: 첫날 무조건 MOC 매도, 개수=전체의 1/10(20분할)·1/20(40분할), 내림. 매수 없음 | FORM(리버스 별지점 절): 첫날은 별지점 없이 MOC 매도만 | REVPOS:28-32 `holdings / (n/2)`; REVSTR:28-37 MOC SELL 가격 0; `CycleOrderComputer.java:~67` 첫날=직전 행이 일반모드 | 일치 | 30분할은 n/2=15 → 1/15(LIT 30분할 불명확 §E5). 분모는 n/2 정수 나눗셈(n 홀수면 내림) |
| R3 | 둘째날 이후 매도 | §A6·§B2: 별지점 위에서 매도, 수량=직전 보유의 1/10·1/20. 가격 종류(LOC 여부)는 §E7 불명확, 계산기 표기는 "별지점 위 LOC매도" | FORM: LOC 매도 가격=starPointPrice | REVPOS:35-39, REVSTR:48-54 `LOC SELL qty=holdings/(n/2) @ starPointPrice` (leg `REVERSE_INFINITE_LOC_SELL`) | 일치 | 수량은 "직전 보유"=전일 마감 원장 보유 |
| R4 | 별지점(리버스) | §A6: 직전 5거래일 종가 평균 | FORM "리버스모드 별지점": 현재 사이클 INFINITE 상세 있는 최신 cycle_position **5건**의 closingPrice 평균 scale2 HALF_UP, 무효 종가 제외 | `CycleOrderComputer.java:113-125` 동일, `STAR_POINT_WINDOW=5` | 차이(미세) | LIT은 "거래일 5개"(월요일 본장 전엔 지난주 월~금). KISTA는 **저장된 행 5건**(거래일과 일치하는 것이 보통이나 보장 아님, 사이클 시작 후 5건 미만이면 있는 것만 평균 — 평균 개수가 5 미만일 수 있음). 수동 보정·로테이션 행 제외 |
| R5 | 매수(쿼터매수) | §A6: 둘째날부터 잔금/4를 별지점 아래에 LOC 매수, 잔금=직전 매도금 누적 포함. 가격 −0.01 여부는 §E7 불명확 | FORM: 쿼터매수 LOC 가격=starPointPrice−0.01, 수량=floor(round2(deposit/4)÷주문가) | REVPOS:42-62 `deposit/4`(scale2 HALF_UP)÷(star−0.01) FLOOR; REVSTR:57-63 | 일치(LIT 불명확부는 KISTA가 −0.01 채택) | deposit=원장 예수금(전 매도금 누적 포함) → LIT 예시 (400+300)/4=175와 동형. LIT 예시는 "잔금 400$ 진입" |
| R6 | 쿼터매수 예산 소진 | (없음) | workflow-capper.md: 별지점은 있으나 deposit/4로 1주도 못 사면 매수 대신 동일 수량 MOC 매도(`REVERSE_INFINITE_QUOTA_MOC_SELL`). 계획 시점 cap 전 가격으로 판정 | REVPOS:66-69, REVSTR:41-44,69-78 | KISTA 고유 | LIT에 없음. 매도를 앞당기는 동작(손실 확정 가속) |
| R7 | 3/4 지정가(+15/+20%) 유지 | §E7: 리버스 중 유지 여부 미기재 | (언급 없음) | REVSTR에는 지정가 매도 없음 — 리버스 주문은 LOC 매도·LOC 매수·MOC 매도뿐 | 문헌 불명확 | KISTA는 리버스 중 지정가 미접수(진입 시 일반모드 지정가는 해당 일 AT_OPEN에 이미 접수·잔존 가능성은 코드 미확인) |
| R8 | T 갱신(리버스) | §A6: 20분할 매도 T×0.9, 매수 T+(20−T)×0.25; 40분할 ×0.95, +(40−T)×0.25 | (없음) | T 저장 없음. 복귀 후 R은 원장에서 재계산 | 차이 | KISTA는 리버스 중 T를 추적하지 않는다. 복귀 시 LIT "T는 리버스에서 쓰던 값 그대로"와 달리 R=매입액/unitAmount(원가·실현손실 반영). 수치 비교 미수행(7절) |
| R9 | 종료·복귀 | §A6·§B: 종가가 평단 대비 TQQQ −15%/SOXL −20% "보다 커지면" 확인 다음날부터 일반모드. 종료선=일반모드 별% 끝값 | FORM(리버스 절)엔 종료 조건 설명 약함; `isFinalRound` 절에 리버스 진입만 | REVPOS:73-78 `closingPrice ≥ round2(avg×(1−targetProfitRate))` (holdings>0일 때만, INFPOS:100-107), 체결 후 마감 시점 종가 기준 | 차이(경계) | ① LIT "보다 커지면"(>)과 KISTA `≥`. ② 임계는 평단×(1−r)로 n과 무관 → LIT와 동일(20·40분할 공통). 평단은 체결 후 평단 |
| R10 | 복귀 후 1회매수금 | §A6: 일반 공식 잔금/(n−T), T 승계, 다시 소진이면 재리버스 | | 일반모드 unitAmount=(deposit+매입액)/n, 매일 재판정(isFinalRound) | 차이(R8과 같은 뿌리) | |
| R11 | 리버스 주문의 가격 캡 | (없음) | workflow-capper.md: 쿼터매수 가격 min(별지점−0.01, 현재가×1.05), 수량은 deposit/4÷캡가 | INFCYC:155-171 | KISTA 고유 | 4절 |
| R12 | 일반모드에서의 isFinalRound MOC 매도 | (V2.2 쿼터손절·V3.0 쿼터모드와 유사한 "MOC 1/4") | FORM: 후반 unitAmount>deposit이면 MOC 매도(holdings/4)만 | INFSTR:162-168 | 6절 참조 | 정상 흐름에선 체결 후 판정으로 리버스 플래그가 먼저 켜져 이 분기가 일반모드에 남을 가능성은 낮다. 플래그와 별개로 위치가 final이면 1/4 MOC(리버스 1/10~1/20과 다름) |

## 3. VR 대조표

| # | 항목 | LIT (파일:절) | KISTA 문서 | KISTA 코드 | 판정 | 차이 설명 |
|---|---|---|---|---|---|---|
| V1 | V 갱신식 | value-rebalancing.md §A2: V' = V + P/G + C (C: 적립+/거치0/인출−) | FORM VR: `V' = V + pool/G + recurringAmount + (평가금−V)/(2√G)` | VRPOS:70-87 `nextValue`; `VrCycleRolloverService.java:~118-125` 호출(pool=postBalance.deposit(적립/인출 반영 전), evaluation=holdings×due일 종가) | **차이(큼)** | `(평가금−V)/(2√G)` 항은 LIT에 없다(LIT: 5.0은 단일 식, 이전 버전 규칙은 사이트에 없음 §E U7). 평가금이 V보다 높으면 V가 추가 상승, 낮으면 추가 하락(추세 추종 방향). G=10이면 계수 1/6.3, G=20이면 1/8.9 |
| V2 | G 값 | §A6: 적립·거치 /10 시작, 인출 /20 시작→/30, 1년 단위 /11→/12→/13 | FORM 램프 표: 적립 10→gMax 20, 거치 10→20, **인출 40→50**; 유예 52주·단계 26주마다 +1 | `VrRampInput.java:25-41` 기본값, `StrategyVrDetail.java:33-39` | 차이 | ① 인출식 시작 G: LIT 20(→30), KISTA 40. ② 단계 주기: LIT 1년, KISTA 26주 기본(유예 52주 후 26주마다 +1). ③ 자동 램프 자체는 LIT에 없음(LIT은 수동 점진 권고) — KISTA 자체 확장 |
| V3 | 밴드 | §A3: 상 V×1.15·하 V×0.85, ±10/±5/±20 허용 | FORM: lower=V×(1−w/100), upper=V×(1+w/100) | VRPOS:39-47 | 일치 | 기본 15, 허용값 10/15/20(`TradingPolicySettings.defaults()` VR). ±5% 선택지 없음(LIT은 허용 언급) |
| V4 | 판단·목적지 | §A4: 평가금>상단 → V까지 매도, <하단 → V까지 매수(Pool 한도). 밴드 안 거래 없음. 흔한 실수: 밴드 경계까지만 되돌림 | FORM: 사다리 `buyPrice(m)=lowerBand÷(holdings+m−1)`, `sellPrice(s)=upperBand÷(holdings−s+1)` | VRPOS:54-62, VRSTR:122-152(매수 최대 20단 1주씩 LIMIT AT_OPEN, 병합), VRSTR:182-200(매도 20단, holdings>20이면 마지막 단 잔여 전량) | **차이** | 사다리는 평가금이 **밴드 경계(lowerBand/upperBand)** 로 복귀하는 가격에서 1주씩 체결되는 구조(m단에서 보유 h+m−1주 평가금=lowerBand). LIT는 목적지를 V로 규정. LIT는 주문표 산식 자체가 사이트에 없음(§E U1)이므로 "LIT 불명확 + KISTA 계단식 해석". 최대 20단 한도(LIT 없음) |
| V5 | Pool 사용 한도 | §A5: 적립 75%(적립 후 Pool), 거치 50%, 인출 25%(인출 후 Pool); 점차 낮추는 방향 | FORM: `poolLimit = 개장 pool × poolLimitRate`, 램프 표 적립 1.0→floor .5, 거치 .75→.5, 인출 .1→.1; 유예 52주 후 26주마다 −5%p | `StrategyVrDetail.java:42-55`, VRSTR:78-87 `governanceLimit = poolLimit−poolUsed`(poolLimit=0이면 pool), `min(pool)` | 차이 | 초기 한도율: 적립 100%(LIT 75), 거치 75%(LIT 50), 인출 10%(LIT 25). 한도 기준이 "사이클 개장 시점 pool × 율 − 사이클 누적 매수액"(LIT: 사이클 시점 Pool×율). 램프는 KISTA 확장 |
| V6 | 적립·거치·인출 | §A9: 적립 +C, 거치 0, 인출 −C | recurringAmount 부호로 구분, 기본 거치(HOLD) | `VrCycleRolloverService.java:~108-111` `adjustedPool = deposit + recurringAmount`로 개장 예수금에 반영, V에도 recurringAmount 가산 | 일치(P의 시점은 LIT §U2 불명확) | P/G의 P는 적립·인출 반영 **전** 예수금. 인출 후 음수면 롤오버 보류 |
| V7 | 주기 | §A7: 2주 1회 | FORM: `intervalWeeks`, due=startDate+intervalWeeks | 기본 2, 허용 1/2/4 (`TradingPolicySettings.defaults()`) | 일치+확장 | 1·4주 선택은 LIT 없음 |
| V8 | 주문 방식 | §A7: 지정가/LOC로 2주치 예약, 사이클 첫날 주문표 작성 | workflow-*: 사다리는 LIMIT+AT_OPEN | VRSTR `LIMIT`, `AT_OPEN` | 차이(운영) | KISTA는 매일 사다리를 재생성·접수(사이클 시작일 고정 표 아님). 접수 전 cap(현재가×1.05) 재산정 |
| V9 | 0일차 | §B/§C: TQQQ 금액=원금/(1+P/V), P/V 표(G10 0.15…) | FORM bootstrap: V=0이거나 사다리 첫 단 > 잔여예산이면 `remainingBudget` 전액을 cap가(현재가×1.05) LOC 1건 | VRSTR:33-35,68-71,93-103 | 차이 | LIT는 비율 배분(0.15 P/V). KISTA는 초기 V·예수금을 사용자 입력/시장가로 정하고 bootstrap은 예산 전액 매수 — 비율 개념 없음 |
| V10 | 램프(`VrRampParams`) 등 자체 확장 | (없음) | FORM VR 절 | `VrRampInput/VrRampValidator`, `StrategyVrDetail` | KISTA 고유 | 4절 |
| V11 | 종목 | TQQQ 중심 | VR=TQQQ 고정 | `StrategyType` VR capability | 일치 | |
| V12 | 사이클 종료·holdings 0 | 사이클 끝 없음 | `endsCycleOnLiquidation=false` | VrCycleOrderStrategy.java:35 | 일치 | |

## 4. KISTA 고유 요소 (LIT에 없음)

| 요소 | 내용 | 근거 문서·코드 | LIT와 충돌 여부 |
|---|---|---|---|
| 매수 가격 캡 1.05 | LOC 매수가가 `현재가×1.05` 초과면 캡 가격으로 대체, 캡 기준으로 수량 재산정(전반 ①②·후반 단일). 접수(마감 30분 전) 시점 현재가 기준 | `PriceCapPolicy.java:20-28`, `InfiniteStrategy.java:43-56,81-117`, FORM "cap 배수 1.05 경위" | 충돌 소지: LIT 첫 매수는 종가 +10~15% 큰수를 규칙으로 서술(증권사 ±20% 제한 우회용). KISTA는 캡으로 ±5%에 묶어 큰수·+15%/+20% 별지점 매수가 실질 무효화(캡이 걸리면 가격↓, 수량↑). 대가: 마감 30분 내 5%↑ 반등 시 미체결(FORM 경위: 36일 중 1일) |
| 보정 주문 3건 | base BUY 뒤 LOC 1주×3, 가격 unitAmount/(누적수량+1) FLOOR. 상시 부착(cap 무관), 예수금 초과 시 뒤에서부터 제거 | `InfiniteStrategy.java:24,71-78,129-140`, FORM "보정 주문", `workflow-capper.md` | LIT 사이트 코드의 "아래 단계 LOC"와 식 동형(R/(V+k)), 단 3단/FLOOR. 본문 규칙엔 가격식 없음 → 충돌 아님/불명확 |
| 원장 상한(`StrategyLedgerBudget`) | 전략별 `cycle_position.deposit` 안에서만 BUY, 초과 시 보정 생략→base도 안 되면 BUY 전체 거절 | `workflow-ledger-price.md` | LIT: "설정 원금 안에서 해결"과 정신 같음. 같은 계좌 다전략은 LIT "계좌 분리 권장". 충돌 없음 |
| 마감 배치 매도·매수 동시 접수 | LOC 매수·LOC 매도 모두 마감 배치(04:30 KST)가 같은 position으로 접수, 지정가 매도만 개장 배치 선접수 | `workflow-batch.md` "leg-aware", `InfiniteStrategy.java:183-198` | LIT: LOC는 본장 중 언제든, 지정가는 프리장 시작에. 접수 시점만 다름(LIT 매수/매도 동시 허용 Q&A와 일치). 충돌 아님 |
| 쿼터매수 예산 소진 → MOC 매도 | 리버스 R6 | `ReverseInfiniteStrategy.java:41-44,69-78` | LIT 없음 |
| 리버스 가격 캡 | 별지점−0.01을 캡으로 하향, 수량 재산정 | `InfiniteCycleOrderStrategy.java:155-171` | LIT 없음 |
| 최소 시드 | `price × n × 2` | `InfiniteCycleOrderStrategy.java:36,148-152` | LIT 없음(자금 설계 가이드는 있으나 식 없음) |
| 최소 1주 강제 | 전반 ①·②, 후반 매수 모두 | `InfinitePosition.java:117-134` | LIT 사이트 코드는 floor(0주 가능) |
| 전략 종류 | PRIVACY(FIDA 기준 매매표 × 자산비 배수, 수량 가감·SELL cap·버림 보정), DCA(규칙형 적립: 주기·조건 AND·RSI/SMA/FNG), 코인 DCA | FORM "PRIVACY 배수", `matching.md` DCA 절 | LIT에 없는 전략 — 대조 대상 아님 |
| VR 램프 | G·poolLimitRate가 경과 주수로 자동 변화 | FORM VR 절, `StrategyVrDetail.java:33-55` | LIT는 수동 조정(1년 단위 G+1) 권고. 자동화는 확장, 값은 불일치(V2·V5) |
| VR `(평가금−V)/(2√G)` 항 | V 갱신식에 평가금 편차 반영 | `VrPosition.java:67-87` | **충돌**(V1) |
| VR 사다리 20단·병합, bootstrap, 드리프트 보정 | | `VrStrategy.java` | LIT 불명확(U1) |
| 가격·시세 출처(관리자 시세 우선) 및 접수 직전 재조회 | | `workflow-ledger-price.md` | 충돌 아님 |
| 종목 MAGX·USD | 익절률 15%/20% | `StrategyTicker.java:15-16` | LIT 오피셜 외 |
| 별% 1%p 반올림 | priceOffsetRate scale 2 | `InfinitePosition.java:67-73`, 테스트 :52 | **충돌**(1절 #7) |

## 5. LIT에 있는데 KISTA에 없는 것

1. **T 누적 가산 상태값**(1회분 +1, 절반 +0.5, 쿼터매도 ×0.75, 지정가후 재매수 ×0.25+…)과 부분 체결 0.5 단위 반올림(사이트 코드) — KISTA는 원장 비율 방식.
2. **리버스 T 갱신식**(매도 ×0.9/×0.95, 매수 +(n−T)×0.25)과 복귀 시 T 승계.
3. **첫 매수 큰수 단일 주문**(종가 +10~15%에 1회분 전액)과 그 아래 단계 LOC — KISTA는 일반 전반 공식(절반 종가·절반 +r)을 쓴다.
4. **아래 단계 LOC 최대 10단계 및 "기준가보다 낮을 때만 채택" 검사**(사이트 코드) — KISTA는 3단계, 검사 없음.
5. **별%·별지점 소수 정밀도**: LIT 계산기는 별%를 반올림하지 않고(예 14.25%) 별지점만 round2 — KISTA는 별%를 1%p 단위로 반올림.
6. **매수점 별지점−0.01(매도점=별지점)** — KISTA는 반대 방향(#9).
7. **VR**: V 갱신식에 편차 항 없음(V1), 0일차 P/V 표 기반 초기 배분(V9), 밴드 ±5%, 한도율 75/50/25 기본값(V5), 인출식 G/20 시작(V2), 사이클 첫날 2주치 주문표 고정(V8, 산식은 LIT도 없음).
8. **리버스 쿼터매수 −0.01·3/4 지정가 유지 여부**는 LIT도 불명확(§E7)이라 "없음"이 아니라 미정.
9. **재시작 복리/단리 선택**(§A5) — 코드 미판독.
10. **30분할 공식·리버스 비율**: LIT도 미기재.

## 6. KISTA 문서 ↔ KISTA 코드 불일치

| # | 문서 | 코드 | 설명 |
|---|---|---|---|
| D1 | FORM: `currentRound`는 "double, 소수점 허용, floor 없이" | `InfinitePosition.java:52-55` `divide(unitAmount, scale 2, HALF_UP).doubleValue()` | 둘째 자리 반올림이 문서에 없음. `calcCurrentRound`(:58-65)도 동일 |
| D2 | `InfinitePosition.java:92` 주석: `unitAmount > deposit ⟺ currentRound > 19` | 식은 R>n−1 | 19는 20분할 한정. 30분할 29, 40분할 39(주석·일반화 오류, 동작 영향 없음) |
| D3 | FORM: 전반 매수①②는 "최소 1주" 명시, 후반은 언급 없음 | `lateBuyQuantity`도 `max(1, …)` (:131-134) | 후반 최소 1주(예: unit<기준가여도 1주) 문서 누락 |
| D4 | `workflow-capper.md` 보정 "가격 = K / (누적수량 + 1)" | `addCorrectionOrder` 분자=`position.unitAmount()` | 기호 K가 정의되지 않음(FORM은 unitAmount로 정확히 표기) |
| D5 | FORM "리버스모드 별지점 — 최신 5건" | LIT·DOC 모두 "5거래일"로 읽힐 수 있음 | 코드는 저장 행 5건(유효 종가만) — 문서는 정확하나 5건 미만이면 평균 개수가 줄어드는 점이 명시 안 됨 |
| D6 | FORM: 리버스 종료 조건 서술 약함 | `ReverseModePosition.shouldExit` `≥ round2(avg×(1−r))`, holdings>0 | 종료 조건(≥, 마감 종가 기준, 체결 후 평단)이 FORM 본문에 식으로 없음 |
| D7 | FORM VR 램프 기본표 | `VrRampInput.java:25-41` | 일치 확인(적립 10/20/1.0/0.5, 거치 10/20/0.75/0.5, 인출 40/50/0.1/0.1) |
| D8 | FORM "보정 주문: 리버스 제외, isFinalRound 제외" | `buildLateStageOrders` isFinalRound 분기에서 보정 미부착 ✓ | 일치 |

(그 밖의 FORM 항목 — averagePrice·unitAmount·referencePrice·targetPrice·VR 사다리·bootstrap 규칙 — 코드와 일치 확인. PRIVACY·DCA·재시작/롤테이션 코드는 이번 대조 범위에서 미판독.)

## 7. 확인 필요 순위 (주문 금액·가격 영향 큰 순)

1. **별% 1%p 단위 반올림** (INFINITE 전 구간): LIT 14.25% → KISTA 14%. 별지점이 평단 기준 최대 ±0.5%p, T 소수 구간에서 매 회차 가격이 LIT 계산기와 어긋남(TQQQ 40분할 평단 50, T=1: KISTA 57.00 vs LIT 57.13). 단위 테스트가 고정(의도로 보임). 문헌: **명확**(계산기 값 14.25 등 §B3). 단 LIT 계산기는 제3자 구현.
2. **T 정의/변화 방식 차이**(원가 비율 R vs 누적 가산 T): 1회매수금과 별%를 매 회차 달라지게 함(특히 쿼터매도 직후, 부분 체결, 리버스 구간 및 복귀). 수치 비교 미수행. 문헌: **명확**(§A2)하나 부분 체결·동시 매도 처리는 불명확(§E2,E3).
3. **VR V 갱신의 (평가금−V)/(2√G) 항**: V 자체가 달라져 모든 사다리 가격·한도에 영향. 문헌: **명확**(통일식 V+P/G+C, 다만 사이트에 이전 버전 규칙 없음).
4. **VR 사다리 목적지(밴드 경계 vs V)와 단계 구조**: 문헌은 목적지 V만 명확, 주문표 산식은 **불명확**(U1).
5. **VR 인출식 G 40(LIT 20→30)·Pool 한도율 초기값(100/75/10% vs 75/50/25%)과 자동 램프**: 매수 가능 금액·V 성장률 직접 영향. 문헌: **명확**(표) 단 램프는 문헌에 없음.
6. **LOC 매수/매도의 0.01 위치**(매수=별, 매도=별+0.01 vs 매수=별−0.01, 매도=별): 매수 체결 임계 +0.01, 매도 임계 +0.01. 문헌: **명확**(§A3). 영향은 종가가 정확히 경계일 때만.
7. **전반전 ①② 수량 배분 순서**(평단 절반 고정 vs 별지점 절반 고정)와 최소 1주: 두 가격 사이 수량 비중과 1회매수액 초과 가능성. 문헌: 본문은 "절반/절반" 명확, 자투리 배분은 사이트 코드(해석)에만.
8. **첫 매수 구성**(절반 종가+절반 +15/20% vs 전액 +10~15% 큰수) 및 캡 1.05: 첫날 체결 가격·수량. 문헌: 큰수 비율 10~15% 서술, 규칙 자체 아님(증권사 실무 장치) → **불명확**.
9. **리버스 진입의 추가 조건 `referencePrice>deposit` 및 쿼터매수 예산 소진 시 MOC 매도**: 리버스 진입 시점·매도 속도. 문헌 **명확**(진입 T>n−1), 소진 처리는 문헌에 없음.
10. **리버스 종료 `≥` vs "보다 커지면"** 및 별지점 5행 평균(5거래일 아님, 5건 미만이면 축소 평균): 복귀 시점·리버스 매도 가격. 문헌: **불명확**(경계·별지점 윈도 정의 §E7).

> 비고: 사이클 재시작 금액 정책, PRIVACY/DCA, 백테스트 엔진은 이번 대조에서 읽지 않았다. 과거 결정 문서(`05-frozen-decisions.md` P항목·6절)는 시간 한도로 대조하지 못했다 — FORM의 cap 1.05 경위만 확인(1.05 유지 결정, 2026-10-25 재평가).
