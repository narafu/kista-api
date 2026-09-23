# 매매·VR 공식 — 변경 금지 SSOT

이 파일의 공식은 단위 테스트로 검증되며 **변경 금지**다. `matching/`(계산 커널)·`trading/`(실행) 양쪽에서 작업 시 자동 로드된다.

### 매매 공식 (변경 금지 — 단위 테스트로 검증)
```
averagePrice (holdings==0이면 prevClosePrice 전일종가)
purchaseAmount = averagePrice × holdings
totalAssets = usdDeposit + purchaseAmount
unitAmount = totalAssets ÷ divisionCount  (scale=2, HALF_UP — 분모는 리터럴 20이 아닌 divisionCount; 허용값 20/30/40 — RuntimeSettings 기본값·capability 메타 availableDivisionCounts() 동기화됨, 기본값은 20)
currentRound = holdings==0 ? 0.0 : purchaseAmount ÷ unitAmount  (double, 소수점 허용)
priceOffsetRate = targetProfitRate × (1 - 2×currentRound/divisionCount)  (scale=2, HALF_UP)
referencePrice = averagePrice × (1 + priceOffsetRate)  (scale=2, HALF_UP — LOC 주문 가격 기준)
targetPrice = averagePrice × (1 + targetProfitRate)  (scale=2, HALF_UP)
```
- `usdDeposit` = 통합주문가능금액 (KIS `TTTC2101R` `itgr_ord_psbl_amt`, 미국 행 필터링) — 원화 자동 환전 포함, totalAssets 계산에 사용
- `currentRound`는 floor 없이 소수점 허용
- **전반/후반 분기**: `priceOffsetRate > 0` → 전반, `≤ 0` → 후반 (수학적으로 currentRound < divisionCount/2 여부와 동치)
- **전반**: LOC 매수①(unitAmount/2/averagePrice, 평단가) + LOC 매수②((unitAmount − averagePrice×매수①수량)×(1+priceOffsetRate)/referencePrice, 기준가) + LOC 매도(holdings/4, referencePrice+0.01) + 지정가 매도(holdings-holdings/4, targetPrice)
- **후반 unitAmount>usdDeposit**: MOC 매도(holdings/4)만 / **후반 unitAmount≤usdDeposit**: LOC 매수(unitAmount/referencePrice, referencePrice) + LOC 매도 + 지정가 매도

### VR 공식 (변경 금지 — 단위 테스트로 검증)
```
lowerBand = V × (1 − bandWidth/100)  (scale=2, HALF_UP)
upperBand = V × (1 + bandWidth/100)  (scale=2, HALF_UP)
buyPrice(m)  = lowerBand ÷ (holdings + m − 1)  (scale=2, HALF_UP, m=1..20, divisor<1이면 skip)
sellPrice(s) = upperBand ÷ (holdings − s + 1)  (scale=2, HALF_UP, s=1..20)

V' = V + pool/G + recurringAmount + (평가금 − V) / (2√G)  (scale=2 HALF_UP, 중간 scale=10)
     평가금 = holdings × 종가
```
- **gradient(G)·poolLimitRate 램프**: 둘 다 고정값이 아닌 "전략 최초 사이클 startDate부터 경과한 주수(weeks)"에 따라 점진 변화하는 값. 초기값·램프 파라미터(유예·단계주기·상하한) 8개는 전략 등록 시 사용자 입력(`StrategyVrDetail`: `initialGradient/gGraceWeeks/gStepWeeks/gMax/initialPoolLimitRate/pGraceWeeks/pStepWeeks/poolLimitFloor`), 생략 시 recurringMode(적립/거치/인출) 고정값 표(kista-ui `RAMP_DEFAULTS_BY_MODE`와 동기화) + 유예 52주·단계 26주로 채운다 — gGraceWeeks/gStepWeeks/pGraceWeeks/pStepWeeks 4필드만 생략 시 관례값, 나머지 4필드(initialGradient/gMax/initialPoolLimitRate/poolLimitFloor)는 아래 표 그대로
    | recurringMode | initialGradient | gMax | initialPoolLimitRate | poolLimitFloor |
    |---|---|---|---|---|
    | 적립(`recurringAmount>0`) | 10 | 20 | 1.0 | 0.5 |
    | 거치(`recurringAmount==0`) | 10 | 20 | 0.75 | 0.5 |
    | 인출(`recurringAmount<0`) | 40 | 50 | 0.1 | 0.1 |
  - `gradientAt(weeks)`: `weeks < gGraceWeeks` → `initialGradient`; 이후 `gStepWeeks`마다 `+1`(고정, `StrategyVrDetail.G_STEP`), `gMax` 상한
    - `gStepWeeks=0`은 gradient 램프 자체를 비활성화(항상 `initialGradient` 유지) — 이때 `gMax`·`gGraceWeeks`는 무관해지므로 0 허용(등록·재설정 양쪽 `gStepWeeks > 0`일 때만 `gMax >= initialGradient` 강제)
  - `poolLimitRateAt(weeks)`: `weeks < pGraceWeeks` → `initialPoolLimitRate`; 이후 `pStepWeeks`마다 `-5%p`(고정, `StrategyVrDetail.POOL_LIMIT_STEP`), `poolLimitFloor` 하한(scale=2 HALF_UP)
    - `pStepWeeks=0`은 poolLimitRate 램프 자체를 비활성화(항상 `initialPoolLimitRate` 유지) — 이때 `poolLimitFloor`·`pGraceWeeks`는 무관해지므로 검증 없이 0 허용(등록·재설정 양쪽 `pStepWeeks > 0`일 때만 `0 < poolLimitFloor <= initialPoolLimitRate` 강제)
  - G·poolLimitRate 두 램프의 유예·단계주기는 서로 독립
  - weeks 재계산 시점: 사이클 롤오버(`VrCycleRolloverService`) 및 운영 중 재설정(`VrReconfigureService`) — 둘 다 `ChronoUnit.WEEKS.between(전략 최초 사이클.startDate, today)`. 사이클 진행 중엔 `strategy_cycle_vr` 스냅샷(gradient·poolLimitRate) 고정
- 등록 검증: `initialValue`, `initialUsdDeposit`, `recurringAmount` null은 0으로 취급
- 적립식(`recurringAmount > 0`): 초기 V와 초기 시드가 모두 0이어도 등록 가능
- 거치식/인출식(`recurringAmount <= 0`): `initialValue + initialUsdDeposit > 0` 필수
- 인출식(`recurringAmount < 0`): `initialValue + initialUsdDeposit >= abs(recurringAmount) × 100 × (4 / intervalWeeks)` 필수 — 운영 중 재설정으로 `recurringAmount`를 인출식으로 바꾸는 경우도 `VrReconfigureService`가 동일 규칙 재검증
- **개장 금액 계약**: `initialUsdDeposit` = 사이클 개장 USD pool(개장 `CyclePosition.usdDeposit`), `startAmount` = 개장 예수금 + 개장 보유분 시장가(모든 전략), `poolLimit` = 개장 pool × `poolLimitRate` (scale=2 HALF_UP). 보유분 시장가를 pool에 포함하지 않는다.
- **종료 금액 계약**: VR 롤오버 `endAmount` = 마감 예수금 + 보유분 종가 평가액(scale=2 HALF_UP). 재설정은 이전 사이클을 자본 조정 전 포지션의 총자산으로 종료하고 새 사이클을 자본 조정 후 총자산으로 시작해 주입/인출을 이전 사이클 손익에 포함하지 않는다.
- **레거시 통계 호환**: Stats는 VR 개장 포지션의 `usdDeposit + holdings × closingPrice`를 개장 원금으로 사용한다. 개장 holdings가 양수인데 `closingPrice`가 null이면 시장가 복원이 불가능하므로 저장된 `startAmount`를 유지한다. 비-VR 계산은 저장된 `startAmount`를 그대로 사용한다.
- **`strategy_cycle_vr.pool_limit_rate`**(비율, 달러 아님)를 스냅샷 저장. poolLimit(달러)은 저장하지 않고 조회 시점에 개장 `CyclePosition.usdDeposit × poolLimitRate`로 파생 — 첫 사이클은 `poolLimitRateAt(0)`, 롤오버·재설정 사이클은 `poolLimitRateAt(weeks)`를 저장
- **bootstrap 진입 판정(`VrStrategy.buildOrders`/`needsBootstrap`)**: `firstCycle` 개념 없이 순수 상태 기반으로 게이팅. holdings=0인데 V=0이면 사다리 공식 자체가 무의미(lowerBand=0)해 bootstrap 대상. holdings=0이고 V>0이어도 사다리 첫 유효 단(m=2, 가격=lowerBand 그대로)이 잔여예산을 초과하면 마찬가지로 bootstrap 대상 — `nextValue()` 공식이 holdings와 무관하게 매 롤오버 V를 키우므로(`pool/G+recurringAmount` 항), holdings=0이 지속되면 V가 예산 대비 과도하게 커져 사다리로는 영원히 매수가 불가능해질 수 있기 때문
  - **holdings>0 드리프트 케이스**: 등록 시 V=시장가×수량으로 확립되지만, 이후 holdings가 늘지 않는 채로 롤오버가 반복되면(`nextValue()`가 holdings 무관하게 V를 계속 키움) 사다리 첫 유효 단(m=1, divisor=holdings)조차 잔여예산을 초과할 수 있다. 이 경우도 매수만 bootstrap(예산 내 캡 가격 LOC)으로 대체하고, 매도 사다리는 이 드리프트와 무관하게 정상 생성한다(전량 bootstrap 전환과 달리 매도까지 사라지지 않음)
- **bootstrap 잔여예산(`remainingBudget`)**: 원칙은 `poolLimit − poolUsed`(이번 사이클에 이미 매수 체결된 금액 차감). 단 `poolLimit`이 0(사이클 개장 시점 예수금 자체가 0이었던 완전 무일푼 시작이라 poolLimit이 그 사이클 내내 영구 고정)이면 DB상 예수금(`pool`, `cycle_position` 최신 스냅샷)을 그대로 상한으로 대신 쓴다. 어느 쪽이든 DB상 예수금은 넘지 않는다(`governanceLimit.min(pool)`). `poolUsed`가 실제 체결 기준이라 부분/미체결 여부와 무관하게 다음날 정확한 잔여예산이 재계산된다. 예산<=0이면 빈 주문(다음 롤오버에서 `nextValue()` 공식이 V를 자연 성장시킴 — 실제로 holdings가 생기면 이 판정 자체가 꺼지므로 별도 처리 불필요)
- bootstrap LOC 가격: `PriceCapPolicy.capFor(referencePrice)`(= referencePrice × 1.05, currentPrice 없으면 전일종가로 대체 가능) — 일반 매수 캡과 동일 기준 사용. 주문 수량은 잔여예산/가격 내림 정수
- 사다리 병합: 동일 가격 연속 rung은 수량 병합(매수), 매도는 holdings>20이면 마지막 단(s=20)에 잔여 전량
- 가격 캡: `buyPrice > currentPrice × 1.05`(`PriceCapPolicy`, INFINITE/PRIVACY의 `BuyOrderPriceCapper`와 공용) 이면 cap 가격으로 교체 — scale=2 HALF_UP (currentPrice=null이면 미적용). VR은 매수 사다리 생성 시점(`VrStrategy.buildBuyOrders`)에는 캡을 적용하지 않고, 접수 직전 `BuyOrderPriceCapper`(`PriceCapMode.VR_POSITION`)가 `VrStrategy.buildCappedBuyOrders()`로 재산정한다 — INFINITE/PRIVACY와 동일한 공통 보정 경로
- rollover due 조건: `cycle.startDate() + intervalWeeks ≤ today` (당일 포함)
- V′ ≤ 0이면 롤오버 보류 — 사이클 유지, 관리자·사용자 알림. V=0·holdings=0인 채로 롤오버가 진행되는 경우도 `nextValue()` 결과를 그대로 쓴다(예전 존재했던 "V 강제 0 유지" 가드는 폐기됨) — `pool/G+recurringAmount` 항으로 다음 사이클 V가 자연 성장하고, 실제 매수는 항상 pool/poolLimit 실측 잔고 한도 내에서만 이뤄지므로 과다지출 위험이 없다
- **운영 중 재설정** (`PUT /api/trading-cycles/{id}/vr-config`, `VrReconfigureUseCase`/`VrReconfigureService`): 밴드폭·주기·적립금·램프 파라미터 수정 + 선택적 자본 주입/인출(수량/예수금)을 "새 `strategy_vr_version` 발급 + 강제 롤오버(현재 사이클 종료→새 사이클 즉시 생성)" 단일 메커니즘으로 처리. VR 전용, 소유권 검증 필수
  - 램프 시계(경과주수)는 재설정해도 리셋하지 않음 — 항상 전략 최초 사이클 startDate 기준
  - 순수 파라미터 수정: V·holdings·usdDeposit 이월. 수량 주입 +N주(단가 Pc): `holdings+=N`, `avgPrice` 가중평균, `V+=N×현재가`. 수량 인출 -N주: holdings·V 감소, 잔여 평단가 유지. 예수금 주입/인출은 usdDeposit만 증감하고 V는 불변
  - 검증 순서: 램프 파라미터·자본 주입 형태(수량 음수 금지 등)·인출식 최소자산 재검증까지 모두 통과한 뒤에만 브로커 미체결 주문 취소(`OrderCancelService`, 별도 트랜잭션이라 이후 실패해도 롤백 불가)를 호출 — 검증 실패 시 브로커에 실주문 취소가 나가지 않도록 순서 고정
