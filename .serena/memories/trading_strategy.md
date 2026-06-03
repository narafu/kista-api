# 매매 전략 패턴

## 매매 공식 (단위 테스트로 검증 — 변경 금지)
```
A = averagePrice (holdings==0이면 currentPrice)
Q = holdings (보유 수량)
M = A × Q (purchaseAmount)
B = usdDeposit + M (totalAssets)
K = B ÷ 20 (unitAmount, scale=2, HALF_UP)
T = Q==0 ? 0.0 : M ÷ K (currentRound, double, 소수점 허용)
S = 0.20 × (1 - 2T/20) (priceOffsetRate, scale=4, HALF_UP)
G = A × (1 + S) (referencePrice, scale=2, HALF_UP — LOC 주문 가격 기준)
P = A × 1.20 (targetPrice, scale=2, HALF_UP)
```

## INFINITE 전략 주문 로직
- **전반 (S>0)**: LOC매수①(K/2/A, 평단가) + LOC매수②((K−A×Q①)/G, 기준가) + LOC매도(Q/4, G+0.01) + 지정가매도(Q-Q/4, P)
- **후반 K>D**: MOC매도(Q/4)만
- **후반 K≤D**: LOC매수(K/G, G) + LOC매도 + 지정가매도

## TradingService 핵심 helper (SSOT)
- `loadBalance(TradingCycle)` → `BalanceLoad` record (잔고 로드+skip 판정)
- `calcInfinite(balance, cycle, price, today, label)` → `InfiniteCalc` record
- `preview()`와 `execute()` 모두 이 helper 경유 — 공식 변경 시 이 두 곳만

## 잔고 조회 방식
- **KIS API 아님** → `TradingCycleHistoryPort.findRecentByCycleId(cycleId, 1)` 최신 이력에서 `AccountBalance` 구성
- 이력 없으면 `IllegalStateException`

## 수동 실행 흐름 (execute)
1. 소유권 검증
2. BLOCKED 확인 (05:00~17:00 KST = 거래 불가)
3. 이중실행방지 (PLACED 주문 존재 여부)
4. `fetchPricesComplete()` — 현재가 조회
5. `phaseA()` — PLANNED 주문 저장
6. `executePlannedOrders()` — KIS 호출 (실패 시 PLANNED cleanup)
7. PostClose 대기 → 체결 확인 → 보정 주문

## PRIVACY 전략
- ticker: 항상 SOXL 강제 (`TradingCycle.Type.resolveTicker()`)
- 배수: `floor(initialUsdDeposit / currentCycleStart, 2)` 동적 산출
- 기준표: `privacy_trades_master` (전역 SSOT, account_id 없음)

## DstInfo.MarketSession
- DIRECT: 17:00~05:00 KST (DST 기준) — 주문 가능
- BLOCKED: 05:00~17:00 KST — 주문 불가
- KIS LOC 주문: DIRECT 창 시작(17:00 KST) 직후 수 분간 EGW00202 발생 가능

## KIS 오류 EGW00202 알려진 원인
1. 미국 공휴일
2. LOC 주문에 가격 "0" 전송
3. DIRECT 창 초반(17:00~17:10 KST) 타이밍 이슈 — KST 18:00 이후 재시도 권장
