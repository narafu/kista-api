# 계좌 예산 배정 제거 → 전략별 자기 원장 상한 설계

작성일: 2026-10-24
범위: kista-api(`:trading-core` 중심, root `:api`의 설정 제거), kista-ui는 API 계약 변경만 기술

## 1. 배경과 의도

- 2026-10-17 KST 거래일, TEST 계좌(MOCK)의 PRIVACY 전략이 자기 원장(`cycle_position.usd_deposit` $177.61)을 넘는 BUY($304.32)를 승인·체결해 원장이 −$126.71이 됐다.
- 승인 경로는 `TradingOrderBudgetAllocator.allocateBuys`다. 이 메서드가 **계좌 합산 잔고**(MOCK은 `MockBrokerAdapter.getLiveBalance`가 계좌 내 전략 원장 합계를 반환)로 판정해서, 같은 계좌의 INFINITE·VR 현금을 PRIVACY가 빌려 썼다.
- 실계좌도 같은 구조다. allocator는 계좌 live 잔고로만 막기 때문에, 계좌에 다른 전략의 현금이 있으면 한 전략이 자기 몫을 넘어 주문할 수 있다.
- 사용자 원칙: **KISTA는 실계좌 잔고를 몰라도 된다.** 전략은 자기 규칙대로 주문한다. 실계좌 현금이 부족하면 증권사가 거절하고, 그날 주문은 실패로 끝난다(사용자 알림). 미리보기의 부족 표시는 경고일 뿐이다.
- 근본 원인 중 하나인 MOCK 체결가 불리(LIMIT은 항상 지정가 체결, 종가만으로 판정)는 이번 범위가 아니다(§9).

## 2. 목표 / 비목표 / 반목표

목표
- 계좌 단위 예산 배정(전략 간 우선순위 경쟁, live 잔고 기반 BUY 승인, SELL 판매가능수량 검증)을 제거한다.
- 전략마다 **자기 원장 상한**을 적용한다: 신규 BUY 총액은 그 전략의 최신 `cycle_position.usd_deposit`을 넘지 않는다. DCA는 제외한다(§4.4).
- 증권사 잔고 부족 거절을 확실히 식별할 수 있으면 사용자 알림만 보낸다(관리자 알림 제외).
- 미리보기 경고 기준을 브로커 live 예수금에서 자기 원장으로 바꾼다.
- 전략 등록의 잔고 검증은 차단에서 등록 전 폼 경고로 바꾼다. `balanceCheckEnabled` 설정은 전부 제거한다.

비목표
- MOCK 체결 시뮬레이션 개선, 원장과 FIDA 체결의 괴리 보정
- 코인 DCA(지갑 기반 경로)
- 원장을 실계좌 잔고에 맞추는 대사 기능

반목표(기술적으로 동작해도 실패로 본다)
- 한 전략이 같은 계좌의 다른 전략 현금을 소비하는 경로가 남는 것
- 잔고 부족이 아닌 거절(자격증명·형식 오류 등)이나 결과 불명을 잔고 부족으로 오판해 관리자 알림이 빠지는 것
- 결과 불명(SUBMITTING)을 거절로 바꿔 재접수·중복 주문 위험을 만드는 것
- VR 사다리처럼 주문이 여러 건인 전략에서 거절 알림이 건별로 쏟아지는 것

## 3. 결정 사항 (사용자 결정)

1. 계좌 단위 예산 배정 제거. 매도 판매가능수량 검증도 제거한다(계좌당 종목 1개라 전략 간 경쟁이 없다).
2. **전략별 자기 원장 상한 1단계 규칙**을 계획 단계에서 모든 증권사(MOCK 포함)에 같은 방식으로 적용한다. 계좌 단위 판정이나 전략 간 우선순위는 없다. 이 규칙이 있으므로 MOCK `place()` 전용 원장 초과 거절은 만들지 않는다(같은 규칙이 계획 단계에서 이미 적용된다).
   - 기각된 대안: "자기 원장 1차 + 실계좌 초과 시만 우선순위 배정" 2단계 판정(복잡도 과다).
3. 증권사 잔고 부족 거절 = 그날 주문 실패, 사용자 알림만 보낸다. 코드가 확실한 것만 잔고 부족으로 분류한다.
4. 사이클 재등록(`CycleRotationService.resolveAvailableBalance`)은 내부 원장 기준으로 통일한다(현재 OFF 동작). 증권사 조회는 하지 않는다.
5. 등록 경고는 등록 **전** 폼에서 보여준다. 가용 예수금은 시드 미리보기 응답에 싣는다.
6. `balanceCheckEnabled` 설정 제거. UI 시드 입력 방식 분기는 설정이 아니라 폼 기능으로 옮긴다(UI 세션 몫).
7. 미리보기 경고는 자기 원장 대비로만 보여주고, DCA는 제외한다.

## 4. 동작 설계

### 4.1 계획 단계: 자기 원장 상한

- 대상: `occupiesSeed()=true` 전략(INFINITE·VR·PRIVACY). 신규 후보의 최종 BUY 총액(cap·보정 포함, 현재 allocator 입력과 같은 값)을 그 전략의 **최신 `cycle_position.usd_deposit`**과 비교한다.
- 이하이면 그대로 저장한다.
- 초과하면 `CycleOrderStrategy.fitBuysToBudget(orders, 자기 원장)`으로 1회 축소한다. INFINITE는 보정을 생략하고, VR은 rung을 절단하며, PRIVACY는 축소 없이 원본을 반환한다. 축소안이 상한 안이면 저장한다.
- 그래도 초과하면 그 사이클의 **신규 BUY 전체를 거절**한다(all-or-nothing은 사이클 단위로 유지). SELL은 영향받지 않는다.
- 같은 날 이미 PLANNED·SUBMITTING·PLACED인 그 사이클의 BUY 금액은 상한에서 먼저 뺀다(재실행·개장/마감 분할 시 이중 지출 방지). 계좌가 아니라 **사이클 단위**로 합산한다.
- 거절 시 기존 `InsufficientBalanceEvent`(사용자 알림, `userId` non-null)를 재사용하되 문구를 "전략 원장 예수금 부족"으로 바꾼다. 관리자 알림은 없다.
- 상한 판정은 순수 계산이다. live 조회도, 계좌 단위 락도 필요 없다. 위치는 builder 재량이다(`TradingCandidatePlanner`에서 allocator를 대체하는 작은 컴포넌트, 또는 `matching` 커널의 순수 함수).

### 4.1a INFINITE 최종회차 판정 확장 (사용자 승인 공식 변경)

- 문제: 후반 매수 수량은 `max(1, floor(unitAmount / referencePrice))`라 최소 1주가 보장된다. `unitAmount ≤ 원장 < referencePrice`이면 1주 금액이 원장을 넘어, §4.1 상한에 매일 거절된다. 최종회차 판정은 `unitAmount > usdDeposit`이라 리버스로도 넘어가지 못하는 교착이 생긴다. 손실이 누적된 소액 시드 사이클에서 드물게 발생한다.
- 변경: `InfinitePosition.isFinalRound()` = `unitAmount > usdDeposit` **또는** `referencePrice > usdDeposit`(남은 원장으로 기준가 1주도 못 사는 상태).
- 영향: 이 판정을 쓰는 두 곳이 함께 바뀐다.
  - 그날 주문: 후반 LOC 매수·매도 대신 MOC 매도(holdings/4)만 낸다.
  - 다음 날 상태: 리버스 진입(`nextReverseMode`).
  - 백테스트가 같은 판정을 쓰는지 확인하고 일치시킨다.
- `trading-formulas.md`의 "후반 unitAmount>usdDeposit" 문구를 함께 갱신한다. 변경 금지 SSOT지만 사용자가 승인한 변경이다.
- 검증: 예시(기준가 $30, 분할 20, 총자산 $440, 원장 $25)에서 최종회차가 true이고 MOC 매도만 나오는지. 기존 경계(`unitAmount > usdDeposit`)의 테스트는 그대로 통과해야 한다.

### 4.2 접수 직전 재캡

- `TradingOrderExecutor.applyCap`의 live 예산 가드(계좌 PLANNED BUY 합계 → `LiveBalancePort`)를 **자기 원장 상한**으로 바꾼다. 예산은 `자기 원장 − 같은 사이클의 스코프 밖 BUY(PLANNED·SUBMITTING·PLACED)`이다.
- 재캡이 총액을 늘리지 않으면 지금처럼 그대로 반영한다. 늘리면 `fitBuysToBudget(예산)`으로 축소하고, 그래도 못 담으면 재캡을 생략하고 기존 PLANNED를 유지한다.
- live 조회 실패 분기는 사라진다.

### 4.3 접수와 증권사 거절

- 접수 경로는 `OrderSubmitter` 하나로 변경 없다. 확정 거절은 FAILED, 결과 불명은 SUBMITTING을 유지한다.
- **잔고 부족 거절 분류**를 추가한다.
  - `BrokerApiException`에 잔고 부족 여부를 표시한다(예: `insufficientFunds()`). **확정 거절(`isRejection()=true`)이면서** 어댑터가 확실한 코드로 식별했을 때만 true다.
  - `OrderSubmitter.rejected()`는 잔고 부족이면 관리자 `TradingErrorEvent`를 생략하고, 사용자 알림 대상으로 기록한다. FAILED 기록은 같다.
  - 그 외 거절과 결과 불명은 지금처럼 관리자 알림을 보낸다.
- **알림 집계**: 사용자 알림은 **전략 × 거래일 × 접수 패스당 1건**이다. 접수 루프가 끝난 뒤 잔고 부족으로 FAILED된 주문을 모아 이벤트 1건을 발행하고, 실패 건수와 일부 접수 여부를 담는다. VR 사다리 10단이 다 거절돼도 알림은 1건이다.
- **증권사별 잔고 부족 코드**(2026-10-24 조사):
  - Toss: `422` + `error.code = insufficient-buying-power` "주문 가능 금액이 부족합니다."(공식 overview `openapi.tossinvest.com/openapi-docs/overview.md`, 공식 OpenAPI JSON) — **분류**
  - KIS: `msg_cd = APBK0988`(레포 `kis-api.md:37`과 과거 운영 사례 기록. 공식 포털은 로그인이 필요하고, 공식 GitHub에는 `APBK` 코드표가 없음) — **분류**. 매수 금액 초과와 매도 수량 초과를 모두 포함하지만 BUY 방향만 분류하므로 문제없다.
  - Meritz: 공식 문서와 GitHub에서 찾지 못함 — **분류 안 함**(관리자 알림 유지). `meritz-api.md`에 갭으로 기록한다.
  - Binance `-2010`, Upbit `insufficient_funds_bid`: 코인 DCA 경로라 범위 밖.
- **잔고 부족 분류는 BUY만** 한다(설계자 판단). SELL `APBK0988`은 판매가능수량 검증이 없어진 뒤로는 대부분 원장과 실보유의 괴리라 데이터 이상 신호다. 그래서 관리자 알림을 유지한다. `BrokerApiException`만으로는 방향을 알 수 없으므로 `OrderSubmitter`가 주문 방향과 함께 판정한다.
- **알림 이벤트**(설계자 판단): `InsufficientBalanceEvent`에 실패 건수와 일부 접수 여부 필드를 추가해 재사용한다. 재주문 즉시 접수는 API 응답으로 결과가 바로 보이므로 별도 알림을 보내지 않는다.
- **접수 순서 보장 없음**(설계자 판단): 지금도 `TradingService.placeAll`은 context 순서로 접수하며, 우선순위 정렬은 allocator 안에만 있었다. 실계좌 현금이 모자랄 때 어느 전략이 거절될지는 접수 순서에 달려 있으며, 이를 허용한다. `BuyPriorityOrdering`과 `allocationPriority()`는 소비처가 사라지므로 삭제한다.

### 4.4 DCA (주식)

- DCA는 원장을 쓰지 않는다(`occupiesSeed()=false`, `usd_deposit` 항상 0). 그래서 자기 원장 상한 대상이 아니다.
- **사용자 결정:** DCA도 다른 전략처럼 오늘 규칙대로 전량 주문한다. 예산 판정이나 live 잔고 조회는 하지 않는다. 부족하면 증권사가 거절하고 FAILED로 기록한다.
- 부족 정책 `DcaShortfallPolicy`(PARTIAL/REJECT)는 **유지**한다. 주식 SELL(가용 보유 대비)과 코인 BUY(지갑 잔고 대비)에서 계속 쓰인다. 이번에 없애는 것은 **주식 BUY에서의 효과**뿐이다: `PlannedOrder.partialAllowed`와 `DcaCycleOrderStrategy.fitBuysToBudget` override를 삭제한다. DB 컬럼, API, 메타는 그대로 둔다. UI에는 정책이 매도·코인에만 적용된다는 안내만 추가한다.
- 감수 사항: DCA는 원장이 없어서, 공유 계좌에서는 다른 전략 현금으로도 살 수 있다. 실계좌 현금이 모자라면 증권사가 거절한다.
- DCA 주문이 증권사 잔고 부족으로 FAILED되면 회차 기록이 끊기지 않도록 `DcaRoundSkippedEvent(INSUFFICIENT_BALANCE)` skip 이력을 남긴다. 사용자 알림도 이 이벤트 경로 하나로 보내 §4.3 알림과 중복되지 않게 한다.
- 잔고 부족으로 분류되지 않는 거절은 skip 이력 없이 FAILED만 남고 관리자 알림이 간다(기존 동작).
- DCA SELL 규칙의 판매가능수량은 검증이 아니라 수량 산출 입력(`DcaPlanInputCollector.loadSellable`)이라 유지한다.

### 4.5 수동 실행 (바로 주문)

- `ManualTradingService`의 allocator 호출과 409 "예수금이 부족합니다"·"보유 수량이 부족합니다"를 제거한다.
- 대신 §4.1 자기 원장 상한을 똑같이 적용한다. 거절되면 409를 유지하고 문구는 "전략 원장 예수금이 부족합니다."로 바꾼다. 축소되면 축소안으로 진행한다.
- 증권사 거절은 배치와 같은 경로로 처리한다.

### 4.5a 재주문

- 재주문(`ReorderService`) BUY에도 §4.1 자기 원장 상한을 적용한다(사용자 결정). 예산은 `사이클 원장 − 사이클 BUY 예약 + 취소될 원본 환불`이고, 넘으면 409로 차단한다. 축소는 하지 않는다. 재주문은 사용자가 지정한 단건이기 때문이다.
- 예산 조회 `ReorderBuyBudgetService`(`ReorderBuyBudget`)를 계좌 값 하나에서 **사이클별 값**으로 바꾼다. live 조회는 제거한다. UI 계약 변경이다(§7).

### 4.6 미리보기

- `TradingBuyCompetitionSimulator`·`TradingSellSufficiencySimulator`·`PreviewDepositCache`를 제거한다.
- `NextOrdersResponse`의 `competition`·`sellSufficiency`를 새 경고 필드로 대체한다.
  - 예: `ledgerCheck { ledgerUsdDeposit, requiredBuyUsd, sufficient, trimmed }` — 이름과 형태는 builder 재량이다.
  - `requiredBuyUsd`는 §4.1과 같은 계산(같은 사이클 기존 BUY 차감 포함)이다. 미리보기가 실제 배치와 같은 함수를 호출해 결과가 어긋나지 않게 한다.
  - DCA는 이 필드를 null로 둔다.
- kista-ui 계약 변경이다(§7).

### 4.7 전략 등록과 사이클 재등록

- `StrategyCreationService.validateBalanceIfRequired` 차단을 제거한다.
- 시드 미리보기(`strategy-seed-preview`, `StrategySeedPreview`)에 **가용 예수금** 필드를 추가한다.
  - 값 = 기존 `calcFreeCash` = `MarginPort.getUsdBuyableAmount − Σ(계좌 내 전략별 최신 usd_deposit)`이다.
  - MOCK(마진 9억 고정이라 무의미)이나 조회 실패면 null이다.
  - 폼은 입력 시드가 이 값을 넘으면 경고만 띄운다.
- `CycleRotationService.resolveAvailableBalance`는 내부 원장 기준으로 통일하고 `fetchUsdBalance`를 제거한다.
  - **MAINTAIN 목표 시드 = `min(원래 시드 startAmount, 직전 사이클 최종 원장)`**(사용자 결정). 현재 OFF 분기는 MAINTAIN이면 `startAmount`를 그대로 반환한다(`CycleRotationService.java:130-133`). 그러면 손실 사이클 뒤 원장이 부풀고, 그 차액만큼 다른 전략 현금을 쓰게 된다. 손실 뒤에는 남은 돈으로 시작하고, 시드를 키우려면 사용자가 시드를 바꾼다.
  - 목표 시드가 최소금액에 못 미칠 때의 관리자 `InsufficientBalanceEvent(userId=null)`는 원장 기준 판단이라 유지한다.

### 4.8 `balanceCheckEnabled` 제거

- root
  - `UserSettings.balanceCheckEnabled`, `PATCH /api/settings/balance-check`(`BalanceCheckRequest`, `UserSettingsUseCase.updateBalanceCheck`), `UserResponse.balanceCheckEnabled`를 제거한다.
  - 복제 이벤트 `UserNotifyProfileChangedEvent`의 필드를 제거한다.
- trading-core: `TradingUserProfile.balanceCheckEnabled`, `UserNotifyProfileEntity` 필드, 동기화 리스너를 제거한다.
- DB(expand/contract)
  - root `public.user_settings.balance_check_enabled`: 이번 배포에서는 코드 참조만 제거한다. 컬럼 DROP은 다음 배포의 별도 마이그레이션으로 한다(`check-migrations.sh`가 DROP COLUMN을 막는다. constraints.md "2-role 배포 backward-compat").
  - trading `trading.user_notify_profile.balance_check_enabled`: trading 마이그레이션은 이 제약 밖이다. 다만 엔티티 제거와 DROP을 같은 배포에 넣을지는 builder가 판단한다. `NOT NULL DEFAULT`면 엔티티만 먼저 빼도 insert는 안전하다.
- Redis Stream 호환
  - 구버전 kista-api가 보낸 payload에 필드가 남아 있어도 신버전 trading-core가 무시해야 한다.
  - Jackson unknown property 설정을 확인하고, 필요하면 `@JsonIgnoreProperties(ignoreUnknown = true)`를 단다.
  - 반대 방향(신 api → 구 trading-core)은 필드가 빠지면 boolean 기본값 false로 역직렬화될 수 있는데, 구 trading-core에서 false는 "검증 끔"이라 무해하다.

### 4.9 제거 대상 정리

| 대상 | 처리 |
|---|---|
| `TradingOrderBudgetAllocator` (+Test) | 삭제, §4.1 상한 판정으로 대체 |
| `TradingBuyCompetitionSimulator`, `TradingSellSufficiencySimulator`, `PreviewDepositCache`, `BuyCompetitionPreview`, `SellSufficiencyPreview` | 삭제 |
| `TradingOrderExecutor`의 `LiveBalancePort` 의존 | 제거, 자기 원장 예산으로 대체 |
| `ReorderBuyBudgetService` live 조회 | 사이클별 자기 원장 기준으로 변경(§4.5a) |
| `BuyPriorityOrdering`, `CycleOrderStrategy.allocationPriority()` | 삭제 |
| `OrderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker` | DCA 입력 수집기만 남으면 유지, 미사용이면 삭제 |
| `OrderPort.sumPlannedBuyByAccountAndDate` | 계좌 단위 합계라 소비처가 사라지면 삭제(사이클 단위 합계로 대체) |
| `PlannedOrder.partialAllowed`, DCA `fitBuysToBudget` | 삭제(§4.4). `DcaShortfallPolicy`는 유지 |
| `AccountBudgetLock` | 예산 용도는 사라지지만 배치·수동 실행 중복 저장 방지(저장 직전 `excludeExisting` 재검사) 역할로 유지. 이름 변경은 builder 재량 |
| `LiveBalancePort`, `SellableQuantityPort` | DCA·통계 등 남은 소비처 확인 후 미사용 메서드만 정리 |
| `MockBrokerAdapter.getLiveBalance`의 원장 합산 | 계획 경로 소비처가 사라지므로 남은 소비처(통계 등) 확인 후 정리 |

## 5. 제약

- 매매 공식(`trading-formulas.md`)은 변경 금지다. `fitBuysToBudget` 구현도 바꾸지 않고 호출 예산만 바꾼다.
- 결과 불명 분류(`isRejection()`)의 의미는 바꾸지 않는다. 잔고 부족은 확정 거절의 하위 분류로만 추가한다.
- `@Transactional` 안에서 외부 호출 금지, `@Async` 금지 등 기존 제약을 지킨다.
- `flows.yml`(마감·개장 배치 "주문 계획·예산 배정", 전략 등록 "잔고 검증", 바로주문 미리보기·수동 실행, :1431 설정 설명)을 갱신한다.
- 문서 갱신 대상: `workflow.md`, `modules/tradingcore/trading.md`, `modules/api/user.md`, `testing.md`(allocator 테스트 패턴), `architecture-map.md`, `*-api.md`(잔고 부족 코드).
- `ErrorCode` 상수 이름 변경·삭제 금지(kista-ui 계약).

## 6. 검증

- 단위
  - 상한 판정: 이하 / 축소 성공 / 거절 / 같은 사이클 기존 BUY 차감 / DCA 제외.
  - 재캡 예산이 자기 원장 기준인지.
  - 잔고 부족 분류: KIS `APBK0988`은 true, 다른 `rt_cd` 실패와 5xx·불명은 false.
  - `OrderSubmitter`: 잔고 부족이면 관리자 이벤트 미발행, 사용자 이벤트 전략당 1건.
- 회귀: 10-17 시나리오. 같은 계좌에 INFINITE(원장 충분)와 PRIVACY(원장 $177, BUY $304)가 있을 때 PRIVACY BUY가 거절되고 INFINITE는 영향받지 않아야 한다.
- 변이 확인: 상한 판정을 잠시 되돌리면 위 회귀 테스트가 실패하는지 확인한다.
- `ModulithArchitectureTest`, `HexagonalArchitectureTest`, `InternalApiContractTest`, `ArchitectureMapTest`, root·trading-core 전체 스위트를 최종 1회 실행한다.

## 7. kista-ui 계약 변경 (UI 세션에 전달)

- `UserResponse.balanceCheckEnabled` 삭제, `PATCH /api/settings/balance-check` 삭제. 설정 화면의 `BalanceCheckSetting`을 제거한다.
- 전략 폼: `balanceCheckEnabled` 분기를 제거한다. 시드는 항상 직접 입력할 수 있게 하고, 시드 미리보기의 가용 예수금(null 가능)을 참고값과 비율 채우기 보조로 쓴다. 초과하면 경고만 띄운다.
- 미리보기 `NextOrdersResponse`: `competition`·`sellSufficiency`를 제거하고 자기 원장 경고 필드를 추가한다.
- 전략 카드(사용자 승인 UI 개선): 오른쪽 금액은 사이클 시작 시드라서 "시작 시드" 라벨을 붙인다. "예수금 부족" 배지에는 `ledgerCheck`의 남은 원장과 필요 금액을 함께 보여준다(예: "원장 $177 / 필요 $304"). 시작 시드가 남은 현금처럼 읽히는 문제를 해결하려는 것이다.
- 재주문 예산 조회 `ReorderBuyBudget` 응답이 사이클별 값으로 바뀐다. 재주문 BUY가 원장을 넘으면 409로 차단된다.
- DCA 규칙 폼의 부족 정책(PARTIAL/REJECT)은 유지하되, 매도·코인에만 적용된다는 안내를 추가한다.
- 수동 실행 409 문구가 바뀐다(문구 매핑을 쓰는 경우 확인).
- 배포 순서: API 응답에서 필드를 제거하는 변경이므로 UI가 새 필드를 쓰고 옛 필드를 optional로 다루도록 먼저 배포하거나, API와 동시에 배포한다. builder가 결정한다.

## 8. 사용자 확인 필요 항목

- 없음. DCA 처리(§4.4), MAINTAIN 시드(§4.7), 재주문 상한(§4.5a)은 사용자가 결정했다.
- 미리보기는 원장 부족만 표시하고 실잔고 부족은 표시하지 않는다. 실잔고 부족은 접수 때 증권사 거절과 사용자 알림으로만 드러난다(사용자 확인).

## 9. 범위 밖 후속

- INFINITE 보정 주문과 cap 점검 결과(공식 변경이 필요해 별도 결정 대상):
  - 보정 주문이 cap 발동 시에만 붙어 하락 폭 5% 경계에서 매수가 계단식으로 바뀜. 의도 여부 확인 → 처리: docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md
  - `earlyBuyQuantity2` 최소 1주 보장으로 회차 금액 초과 가능 → 처리: docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md
  - 보정 가격 HALF_UP 반올림으로 `(N+1)×가격`이 `unitAmount`를 수 센트 넘을 수 있음(FLOOR가 의도에 부합) → 처리: docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md
  - cap 배수 1.05의 근거를 문서화 → 처리: docs/superpowers/specs/2026-10-25-infinite-correction-cap-review-design.md

- MOCK LIMIT 체결가 개선(종가 기준 체결, 지정가 이하 체결 반영)
- 원장과 FIDA·실계좌의 괴리 보정 도구
