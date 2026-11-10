# KRW 응답 금액 정수 반올림 설계

## 의도

KRW 계좌(UPBIT)의 원장 금액이 API JSON에 `15000.00`처럼 소수 2자리로 나간다. 원장 컬럼이 통화와 관계없이 `numeric(20,2)`이고 계산은 `AccountBalance.LEDGER_SCALE`(2)로 반올림하기 때문이다. kista-ui는 `fmtMoney`로 KRW를 정수로 표시해 화면에서는 보이지 않는다. API 계약에서도 금액 자릿수를 통화 규칙(`Currency.amountScale()`, KRW=0)과 맞춘다. 주문 금액과 일별 거래내역(`DailyTransaction.tradeAmount`)은 이미 행 통화 기준으로 반올림한다. 이번 작업은 원장에서 나온 금액도 같은 규칙을 따르게 한다.

직전 설계(`specs/archive/2026-11-09-crypto-currency-aggregation-design.md`)는 이 문제를 비목표로 뺐다. 이번에 범위로 다룬다.

## 현재 상태 (조사 결과)

- 운영 DB(2026-11-10 읽기 전용 조회): 삭제되지 않은 전략은 BINANCE 1, KIS 1, MERITZ 2, MOCK 4건이다. **UPBIT 전략은 0건**이므로 KRW 원장 행이 없다.
- KRW 원장 소수는 등록 때 생기지 않는다. `DepositAmounts.normalizeForRegistration`이 KRW 입력을 정수로 검증한다. 소수는 체결 후 생긴다. `CryptoLedgerRecorder.recordFill`이 `cashDelta`(scale 8)를 더하고 DB `numeric(20,2)`가 한 번 반올림한다. 업비트 수수료(0.05%)는 KRW 소수를 만든다. 업비트 KRW 잔고도 소수를 가진다. 따라서 원장의 KRW 소수는 오차가 아니라 실제 값이다.
- 평단과 종가 같은 KRW **가격**은 정수가 아닐 수 있다. 업비트에서 1,000원 미만 코인은 호가 단위가 1원보다 작다.
- 이미 통화 기준으로 반올림하는 곳: `AccountLedgerTotalsService:41`(`currency.round`), `CryptoPortfolioResponse`(통화 scale HALF_UP), 주문 금액(`DcaCycleOrderStrategy`), 일별 거래내역 `tradeAmount`.

## 결정

### 1. 반올림 위치: 응답 결과를 조립하는 시점 (추천, 채택)

원장 저장과 내부 계산은 지금처럼 `LEDGER_SCALE`을 쓴다. **밖으로 나가는 금액 필드만** 결과를 조립하는 application 서비스나 assembler에서 그 통화의 `Currency.round()`로 반올림한다. 선례는 `AccountLedgerTotalsService`다.

비교한 대안:

- 원장 저장 시점에 KRW를 0자리로 반올림: 채택하지 않는다.
  - 체결마다 수수료 소수를 버려 원장 예수금이 증권사 잔고와 어긋난다. DCA 체결이 반복되면 차이가 누적된다.
  - 컬럼이 `numeric(20,2)`로 남아 JSON에는 여전히 `.00`이 붙는다. 응답 단계 처리가 어차피 필요하다.
  - 평단 나눗셈(`AccountBalance:89`, `CapitalLedgerService:94`)에 통화가 필요해 matching 커널 시그니처가 넓어진다.
- 웹 DTO의 `from()`에서 반올림: 채택하지 않는다. 대상 DTO 대부분이 통화를 갖고 있지 않다(`CycleHistoryResponse`, `CyclePerformancePageResponse.Item`, `EquityCurveResponse`). 통화를 DTO까지 끌고 와야 한다.
- 현상 유지(UI 표시만 정수): 운영 KRW 데이터가 0건이라 당장 영향은 없다. 하지만 사용자가 정리하겠다고 요청했으므로 진행한다.

### 2. 대상 필드: 금액만 반올림한다. 가격과 수량은 반올림하지 않는다

- 대상(금액): 예수금, 총자산·평가금, 원금, 시작·종료 금액, 손익.
- 제외(가격): `avgPrice`, `closingPrice`. 1,000원 미만 코인 때문에 소수가 필요하다. 지금의 원장 2자리(numeric(20,2))도 이 경우 정밀도가 모자라지만 이번 범위가 아니다(아래 "범위 밖 발견").
- 제외(수량): `holdings`(`Quantities.normalize` 규칙 유지).

### 3. 엔드포인트별 적용 (trading-core)

| 응답 | 금액 필드 | 통화 출처 | 반올림 위치 |
|---|---|---|---|
| `GET /api/stats/summary` | 타입별 `realizedPnl`, `unrealizedPnl`, 상위 `totalRealizedPnl`, `totalUnrealizedPnl`, `activePrincipal` | 요청 `?currency=` | `TradingStatsService` `sum()`(통화 scale을 인자로 받게 바꿈)과 `:279`의 `LEDGER_SCALE` 대신 요청 통화 scale |
| `GET /api/stats/equity-curve` | `totalAsset`, `principal` | 요청 `?currency=` | `TradingStatsService.buildPoints` `:387-388` |
| `GET /api/stats/cycles` | 응답 `startAmount`(도메인 `principal`), `endAmount`, `pnl` | `CycleView.currency()` | `TradingStatsService.toPerformance` |
| `GET /api/accounts/{id}/cycle-history` (stats `DashboardController`) | `deposit` | 계좌 통화 | 이력 결과를 만드는 stats 서비스 |
| `GET /api/trading-cycles/{id}/history` (trading `TradingCycleController`, 같은 모양의 별도 `CycleHistoryResponse`) | `deposit` | 전략 계좌 통화 | 이력 결과를 만드는 trading 서비스 |
| 전략 상세·사이클 목록(`TradingCycleResponse`) | `initialDeposit`, `ledgerDeposit` | 전략 계좌 통화 | `StrategyDetailAssembler` |
| DCA 규칙 조회(`DcaRuleResponse.sizing`) | `amount`(정액 매수 금액. `percent`는 비율이라 제외) | 전략 계좌 통화 | DCA 규칙 결과를 만드는 서비스(`DcaConfigService` 등). 엔티티 `scale2`는 DB 컬럼 정규화라 그대로 둔다 |

이미 처리된 응답: `AccountLedgerTotals`, `CryptoPortfolioResponse`, 일별 거래내역, 주문 가격·금액.

KRW가 지나지 않는 응답: 다음 주문 미리보기(`/preview`, `/previews`)는 코인 계좌를 제외한다(`TradingPreviewService:79,106` — 코인 전략은 전부 DCA이고 미리보기 경로가 없다). 포지션 스냅샷도 INFINITE 전용(USD)이다. 변경하지 않는다.

금액이 없는 응답: 벤치마크 투자지수(`BenchmarkComparisonResponse`, `InvestmentPointsResponse`)는 지수와 수익률만 싣는다. 변경하지 않는다.

USD와 USDT는 `amountScale()`이 2라 결과가 지금과 같다. 동작이 바뀌는 것은 KRW(와 JPY, 현재 원장 없음)뿐이다.

### 4. 비율 계산: 반올림 전 값으로 계산한다

- 투자지수(TWR)는 `CyclePosition.totalAssetAtCloseOrNull()`(반올림 없음)을 쓰므로 영향이 없다. 손대지 않는다.
- summary 승률은 사이클별 `realizedPnl()` 부호, 평균 수익률은 사이클별 `realizedPnl()/principal()`을 쓴다. 둘 다 `sum()`을 거치지 않으므로 영향이 없다.
- 사이클 `returnRate`는 반올림 전 `pnl`로 먼저 계산하고, 응답 금액만 반올림한다. 순서가 바뀌면 KRW 사이클 수익률이 흔들린다. 테스트로 고정한다.
- 결과: 같은 입력에서 모든 비율·지수 값은 변경 전과 같다.

### 5. 합계 정합: 한 응답 안의 등식은 지키고, 응답 사이 차이는 1 단위까지 허용한다 (설계자 결정)

- summary: 지금 구조를 그대로 두고 scale만 통화 scale로 바꾼다. 타입별 `realizedPnl`·총손익을 통화 scale로 반올림하고 `unrealizedPnl = 총손익 − realizedPnl`로 둔다. 상위 합계는 반올림된 타입별 값의 합이다. 따라서 `realized + unrealized = 총손익`, `total* = Σ byType`이 한 응답 안에서 정확히 맞는다. 중간 합을 통화 scale로 줄이는 것도 허용한다. 비율은 `sum()`을 거치지 않으므로 영향이 없다.
- cycles: `endAmount`와 `principal`을 반올림한 뒤 `pnl = 반올림 endAmount − 반올림 principal`로 계산한다. `returnRate`는 반올림 전 값으로 계산한다(지금 구조 유지).
- equity-curve와 summary 사이: 점마다 자산과 원금을 따로 반올림하므로 곡선 마지막 손익과 summary 총손익(CQ-70 계약)이 KRW에서 최대 1원 다를 수 있다. 허용하고 `stats.md` CQ-70 서술에 적는다. USD·USDT는 지금과 같다.

### 6. 운영 데이터 보정: 하지 않는다

UPBIT 전략은 0건이다. 저장 방식도 바꾸지 않으므로 마이그레이션이 없다.

## 목표 / 비목표

- 목표: 위 표의 KRW 금액 필드가 JSON에 정수(scale 0)로 나간다. USD·USDT 응답은 바이트 단위로 같다. 비율·지수는 같다.
- 비목표: 원장 컬럼 타입·저장 자릿수 변경, 가격 정밀도, 환율 환산, root(api) 쪽 응답(원장 금액을 그대로 중계만 함 — 아래 확인 항목).
- 실패로 볼 것: 비율·투자지수 값이 하나라도 바뀌는 것, USD 응답 자릿수가 바뀌는 것, 가격 필드가 정수로 잘리는 것.

## 테스트

- `TradingStatsServiceTest`(또는 기존 stats 테스트): KRW 사이클 픽스처로 summary·equity-curve·cycles 금액 scale 0을 확인한다. 같은 픽스처의 `returnRate`·`avgReturnRate`가 반올림 전 값으로 계산된 값과 같음을 확인한다. 기존 USD 테스트가 그대로 통과해야 한다.
- 미리보기·전략 상세·cycle-history: 각 서비스 테스트에 KRW 케이스 하나씩 추가한다(예수금 `12345.67` → `12346`).
- 변이 확인: 반올림을 되돌렸을 때 새 테스트가 실패하는지 본다.

## 문서 갱신

- `docs/agents/constraints.md` 금액 반올림 항목: "원장 저장·내부 계산은 `LEDGER_SCALE`, 응답으로 나가는 원장 금액은 결과 조립 시점에 그 통화 `amountScale()`. 비율은 반올림 전 값으로 계산. 가격·수량은 대상 아님"을 추가한다.
- `docs/agents/modules/tradingcore/stats.md` `:10`의 `totalAssetWithAvgPriceFallback()`(scale=2) 서술에 "응답은 통화 scale"을 덧붙인다. `:24` CQ-70("곡선 마지막 손익 = summary 총손익")에 "KRW는 반올림으로 최대 1원 차이"를 덧붙인다.
- `flows.yml`·README: 흐름·통신·기능 변경이 없어 갱신하지 않는다(builder가 grep으로 재확인).

## kista-ui 영향

JSON 타입(number)과 필드 이름은 그대로다. KRW 값의 소수 자릿수만 바뀌고 UI는 이미 정수로 표시한다. 계약 변경이 아니므로 인계 프롬프트는 짧은 알림 한 줄로 충분하다(구현 끝에 작성).

## 범위 밖 발견 (언급만, 이번에 고치지 않음)

- KRW 평단·종가가 `numeric(20,2)`라 1원 미만 호가 코인에서 정밀도가 모자란다. 별도 과제로 둔다(사용자 확인 2026-11-10). 원장 컬럼 마이그레이션, matching 커널 평단 나눗셈(`AccountBalance:89`), 변경 금지 매매 공식의 USD 평단까지 걸린다. 운영 UPBIT 0건이라 UPBIT 전략 운영 직전에 설계한다.
- `StrategyDcaRuleEntity:106`, `StrategyDcaConditionEntity:70`의 `setScale(2, HALF_UP)`는 DB 컬럼 `numeric(_,2)` 정규화(save 반환값과 조회값 일치)라 결함이 아니다. DCA 경로 금액 자릿수는 CQ-40(`1566604d4`)에서 정리됐다. 응답 `DcaRuleResponse.sizing.amount`만 이번 범위(위 표)에 넣었다.
- `CycleHistoryResponse`가 stats와 trading 두 모듈에 똑같이 있다. `code-quality-backlog.md:582` 유지 목록(외부 계약 분리)이라 통합하지 않는다. 이번 작업에서 둘 다 고친다.

## builder에게 맡기는 것

- 각 서비스에서 통화를 얻는 구체 경로(계좌 조회 재사용 등). 새 조회를 추가하지 말고 이미 로드된 계좌·전략에서 얻는다.
- root(api)가 trading-core 응답을 그대로 중계하는지 확인한다. 중계 중에 재계산하는 금액이 있으면 같은 규칙을 적용한다.
- `CryptoPortfolioResponse`의 "통화 scale HALF_UP" 설명이 실제 구현과 맞는지 확인한다.
