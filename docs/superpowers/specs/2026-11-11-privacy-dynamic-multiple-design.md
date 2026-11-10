# PRIVACY 배수 동적 산정 (자산비 기준) 설계

## 의도

PRIVACY 배수를 사이클 동안 고정된 `strategy_cycle.start_amount ÷ FIDA current_cycle_start`에서 **매 거래일 실제 자산 비율**로 바꾼다. 고정 배수는 추가 투입·수동 매매·FIDA 실현손익을 반영하지 못해 실제 자산 비율과 벌어졌다. 그 차이를 BUY 가감이 억지로 보정하면서 주문이 왜곡됐다.

실제 사례는 메리츠 P계좌 PRIVACY SOXL(strategy `01a16c04-d0d9-773b-983b-d46c3527d9c2`)이다. 11-05에 사용자가 43주를 수동 매수해 보유가 552주(FIDA 112주의 4.93배)가 됐다. 고정 배수 4.58로 목표 보유량을 513주로 잡은 탓에, BUY가 50주에서 11주로 줄었다.

결정은 2026-11-11 사용자가 확정했고, 재논의하지 않는다.

## 공식 (확정)

평가가 `P`는 전일 종가이고, 양쪽 자산에 같은 값을 쓴다.

```
ourAsset  = deposit + holdings × P
fidaAsset = (currentCycleStart + currentCycleRealizedPnl − fidaHoldings × fidaAvgPrice) + fidaHoldings × P
multiple  = ourAsset ÷ fidaAsset   (scale 2, FLOOR)
```

다음 경우에는 매매를 건너뛰고(skip) 관리자에게 알린다.

- `fidaAsset ≤ 0`
- `fidaHoldings > 0`인데 `fidaAvgPrice`가 null
- 내 판단으로 추가한 조건:
  - `ourAsset ≤ 0`. 기존 `initialDeposit ≤ 0` 예외와 같은 성격이다.
  - `P`를 확보할 수 없는 경우. 단 P가 필요한 경우에 한한다(아래 "평가가 출처").

그대로 두는 것: BUY 가감(`target = plan.holdings × multiple`), SELL cap, 수량 버림 보정. `PrivacyStrategy`에서 배수를 소비하는 코드는 바꾸지 않는다.

검증 기준(11-11 주문분, 2026-11-11 운영 DB 조회로 확정):

| 항목 | 값 |
|---|---|
| 예수금 | 2791.71 |
| 보유 | 552 |
| FIDA 보유 | 112주 |
| FIDA 평단 | 102.09 |
| current_cycle_start | 14434.58 |
| 실현손익 | −2339.37 |

전일 종가 P = 56.20(최신 `cycle_position`). 우리 자산 33814.11, FIDA 자산 661.13 + 6294.40 = 6955.53, 배수 **4.86**. 계획 BUY 55.14×11은 53.46 → 보유 초과 −7.68 가감 → **45주**. 사용자 원래 기대치 "약 50"은 2026-11-11 사용자 확인 후 45로 확정했다(체결 후 597주 = FIDA 123주 × 4.86, 남는 예수금은 다음 날 자산에 반영).

## 평가가·예수금 출처

| 경로 | deposit / holdings | P |
|---|---|---|
| 실주문 배치·미리보기·수동 실행 (`CycleOrderComputer.compute`) | 최신 `cycle_position` (`TradingBalanceLoader`와 같은 행) | 같은 최신 `cycle_position.closing_price` |
| 백테스트 (`BacktestEngine.planPrivacyOrders`) | 시뮬레이션 `state.balance` | `candle.close()` (다음 세션 기준 전일 종가) |

- 같은 행의 deposit·holdings·closing_price를 쓰므로 자산 스냅샷이 일관된다. 기존 헬퍼 `CyclePosition.totalAssetAtCloseOrNull()`이 바로 `ourAsset`이다.
- 추가 투입·인출(`CapitalLedgerService`)은 최신 행의 deposit을 갱신한다. 그래서 다음 계산부터 배수에 자동으로 반영된다. start_amount를 거칠 필요가 없다.
- **내 판단:** 최신 행의 closing_price가 null일 수 있다. 예를 들어 보유 0으로 등록한 직후의 `initialSnapshot`이 그렇다. 이때 P가 필요하면(우리 보유나 FIDA 보유가 0보다 크면) 브로커 전일 종가로 폴백한다. 배치는 `PriceSnapshot.prevCloseOrNull`, 미리보기는 기존 `requiresPrevClose` 경로를 쓴다. 폴백까지 없으면 skip하고 알린다. 양쪽 자산에 같은 P를 쓴다는 원칙은 유지한다.

## 구조 변경

- **커널 입력** (`matching`)
  - `PrivacyPlan`에 `avgPrice`(nullable)와 `realizedPnl`을 추가한다. `PrivacyTradeBase`에 realizedPnl을 싣고 `toPlan()`으로 전달한다. 엔티티에는 컬럼이 이미 있다.
  - `PrivacyInputs.initialDeposit`은 평가가 `P`로 대체한다. 이름은 예컨대 `valuationPrice`로 하되 빌더에게 맡긴다.
  - `PrivacyStrategy.buildOrders(balance, P, plan)`가 위 공식으로 배수를 산출한다.
  - 자산 유효성 판정은 순수 계산이므로 커널(`PrivacyPlan` 또는 `PrivacyStrategy`)에 둔다.
- **skip·알림** (계획 단계에서 확정)
  - 커널은 자산 무효 시 전용 예외 `PrivacyValuationException`(`IllegalStateException` 하위)을 던진다.
  - 배치는 기존 `TradingBatchGuard.runSafely`가 전략 단위로 격리하고 관리자 `TradingErrorEvent`를 보낸다. 새 알림 코드는 없다.
  - 미리보기·수동 실행(`StrategyOrderPlanBuilder`)은 이 예외만 잡아 `SkipReason.PRIVACY_BASE_BLOCKED`로 바꾼다. 새 enum이 없으므로 kista-ui는 변경하지 않는다.
- **백테스트**
  - `PrivacyState.cycleStartAmount`를 배수 계산에 쓰는 부분을 제거한다.
  - 무효 자산이 나온 날은 기존 `recordMissingBase`처럼 경고를 기록하고 그 세션은 주문하지 않는다.
  - `BacktestService`의 `privacyStartAmount`(bootstrap)도 제거한다.
- **bootstrapStartAmount 제거**
  - 재검토 결론은 불필요하다는 것이다. `PrivacyCurrentBase.bootstrapStartAmount`와 `StrategyStartAmountCalculator`의 PRIVACY 분기를 지운다.
  - 등록 시 PRIVACY start_amount는 다른 전략과 같이 `deposit + initialStockValue`가 된다.
  - 이렇게 하면 MAINTAIN 시드(`CycleRotationService`)·원장 상한(`StrategyLedgerBudget`) 같은 start_amount 소비처가 실제 자금 기준으로 맞춰진다.
  - 소비처가 사라진 `findSeedPreviewBase`·`findSeedBaseFrom` 등은 함께 정리한다(남은 소비처는 grep으로 확인).
- **`minRequiredDeposit`**: 유지한다(내 판단). `currentCycleStart × 0.5`는 대략 "배수 ≥ 0.5" 최소 시드 임계이고, 등록 폼 미리보기에는 P가 없다. 새 공식과 충돌하지 않는다.
- **컬럼 삭제**
  - 사용자 지시는 "불필요해진 컬럼은 삭제"다.
  - 조사 결과, 이번 변경으로 소비처가 사라지는 DB 컬럼은 없다.
    - `start_amount`는 통계·로테이션·원장 상한에서 계속 쓴다.
    - `current_cycle_realized_pnl`·`avg_price`는 새로 소비된다.
  - 구현 중 발견되면 trading-core 마이그레이션으로 삭제한다.

## 문서

- `trading-formulas.md`: PRIVACY 배수 절을 새 공식으로 교체한다. bootstrap 문단을 제거한다. 변경 금지 SSOT이므로 "2026-11-11 사용자 승인으로 변경" 경위를 한 줄 기록한다. 73·75·76행 주변.
- `privacy.md`: `toPlan()` 필드와 `buildOrders` 시그니처.
- `flows.yml`: 배치·미리보기 단계 중 PRIVACY 배수·skip 조건이 걸린 `desc`/`fail`과 자본 투입 단계(873~885)의 start_amount 서술.
- `code-quality-backlog.md`: 건드리는 파일명으로 grep해 걸리는 항목을 갱신한다.

## 테스트

- `PrivacyStrategyTest`: 새 입력으로 전환한다. 검증 기준 사례(552주/112주, 배수 ≈4.87, BUY ≈50)를 회귀 테스트로 추가한다. skip 조건마다 1건씩 추가한다.
- `BacktestEngineTest`·`BacktestGoldenTest`·`BacktestServiceTest`: 새 배수 기준으로 기대값을 갱신한다. 골든 값이 바뀌면 이유를 커밋에 남긴다.
- bootstrap 관련 테스트(`StrategyStartAmountCalculator`, `PrivacyTradeBaseTest`, `StrategySeedPreviewServiceTest` 등)는 정리한다.

## 비목표

- 배수 응답 필드 노출이나 UI 변경은 하지 않는다.
- PRIVACY 이외 전략은 바꾸지 않는다.
- start_amount 의미를 바꾸거나 통계 원금을 바꾸지 않는다.

## 세부 확정 사항 (빌더 검토 반영)

- **FIDA 값의 시점**
  - 주문 경로의 base는 `findTodayTrade(today)`(releaseDate = D-1)이다. 보유·평단·`current_cycle_start`·실현손익은 D-1 마감 상태이고 오늘 계획 매매만 미래다. 따라서 전일 종가 `P`와 시점이 같다.
  - 사용자는 검증 기준을 같은 필드로 정의했다.
  - 2026-11-11 운영 DB 조회로 확인했다: 최신 `cycle_position`(P=56.20)과 release 11-10 기준표가 같은 미국 세션 마감 기준이다.
- **ourAsset의 출처**
  - 배치·미리보기의 `balance`는 브로커 실조회가 아니라 `TradingBalanceLoader`가 최신 `cycle_position`에서 만든 값이다(`TradingBalanceLoader.java:25-35`).
  - `P`도 같은 최신 행의 `closing_price`를 쓴다. 이 행은 `CycleOrderComputer`가 `cyclePositionPort`로 조회한다(내 판단). 호출부 시그니처는 늘리지 않는다.
- **P 전달**
  - `PrivacyInputs.initialDeposit` 자리를 평가가 `P`로 바꾼다. 기존 `currentPrice` 슬롯은 PRIVACY가 소비하지 않으므로 손대지 않는다.
  - 우선순위: 최신 행 `closing_price` → `compute`의 `prevClosePrice`(폴백).
  - 미리보기에서 폴백이 동작하도록 PRIVACY도 `requiresPrevClose()=true`로 둔다. 배치는 이미 `PriceSnapshot.prevCloseOrNull`을 넘긴다.
- **백테스트 이력**
  - 과거 base 행에 `avgPrice`(보유>0)·`realizedPnl`이 없으면 그 세션은 skip하고 경고를 남긴다.
  - 2026-11-11 조회 결과 운영 기준표 375행 중 평단 누락(보유>0)·실현손익 누락 0건.
  - 백테스트 `P` = 해당 일 `candle.close()`. PRIVACY 대상은 정수 주식이라 소수 보유 이슈는 없다.
- **운영 중 사이클의 start_amount**: 마이그레이션하지 않는다. 결정 6은 "값은 유지"이고, 기존 bootstrap 값도 등록 당시 자산 비율 기반이라 실제 자금과 크게 다르지 않다. 새 규칙은 신규 등록부터 적용한다.
- **가드**
  - `PrivacyPlan`의 `currentCycleStart > 0` 가드는 FIDA 데이터 무결성 가드로 유지한다.
  - `initialDeposit ≤ 0` 예외는 제거하고 `ourAsset ≤ 0` skip으로 대체한다.
  - skip 조건은 `PrivacyValuationException`으로 표현한다. 배치 격리·알림은 `runSafely`가 이미 제공한다.
- **골든 테스트**
  - PRIVACY 골든 기대값이 바뀌면 재생성에 그치지 않는다.
  - 대표 1개 세션의 배수·수량을 손 계산으로 대조하고, 근거를 커밋 메시지에 남긴다.

## 빌더에게 맡기는 것

필드·record 이름, 자산 유효성 판정의 정확한 위치(커널 vs 가드), 백테스트 경고 문구.
