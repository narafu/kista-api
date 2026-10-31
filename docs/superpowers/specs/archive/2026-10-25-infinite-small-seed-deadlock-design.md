# 0회차 소액 시드 교착 방지 — 설계

작성일: 2026-10-25 · 상태: 사용자 방향 결정 완료(선택지 (d)), 설계 승인 대기

## 의도

시드를 점유하는 전략(INFINITE·PRIVACY)의 사이클 원장이 **주식 1주 가격보다 작은 상태로 0회차(holdings=0)** 에 들어가면 교착에 빠진다.

- 매수 계획이 최소 1주로 원장을 넘는다.
- `StrategyLedgerBudget.fit`이 BUY를 전부 거절한다.
- holdings=0이라 SELL도 리버스 진입도 없다.
- 그 결과 매 배치마다 사용자에게 "전략 원장 예수금 부족" 알림만 나간다.

가격이 `원장 ÷ 1.05` 밑으로 내려오면 cap 경로로 1주가 들어가 스스로 풀린다(재현 숫자 → "근거"). 그래서 이 상태는 "영구"가 아니라 "가격 하락을 기다리는 정지"다.

목표는 이 상태가 **만들어지는 입구를 막고**, 막혔을 때 **사용자가 이유와 조치를 알게** 하는 것이다.

운영 현황(2026-10-25 읽기 전용 조회):

- 살아 있는 INFINITE 전략은 1개(KIS USD, holdings 24)이고 교착 상태가 아니다.
- 과거 회전은 1건(2026-09-23)이고 최소 시드 이상이었다.
- 따라서 이 작업은 사고 대응이 아니라 예방이다.

## 목표

1. **사이클 회전:** 다음 사이클 시드가 1주 가격 미만이면 새 사이클을 열지 않고 전략을 PAUSED로 둔다. 기존 "원장 소진(≤0)" 분기를 이 기준으로 확장한다.
2. **재개(resume):** 종료된 사이클을 다시 열 때 시드가 1주 가격 미만이면 재개를 거부한다. 거부 문구는 사용자에게 그대로 보이는 한국어로 쓰고, 삭제 후 재등록을 안내한다.
3. **자동 PAUSED 사용자 알림:** 위 1로 전략이 멈추면 사용자에게 이유와 조치를 알린다. 지금 이 경로는 관리자에게만 알림이 간다(`userId=null`).
4. **등록 서버 검증:** INFINITE 신규 등록 시 시작 금액이 `minRequiredDeposit`(전일종가 × 분할 × 2) 미만이면 400으로 거부한다. kista-ui 등록 폼의 `isBelowMinSeed`와 같은 기준이다.

## 비목표

- **매매 공식 변경.** `trading-formulas.md`의 매수① 최소 1주(`max(1,…)`)는 그대로 둔다.
- **배치 런타임 감지·자동 정지(선택지 b).** 회전 이후 가격이 올라 생기는 교착은 지금처럼 매일 알림으로 둔다. 가격이 내려오면 자연 회복될 수 있기 때문이다.
- **알림 중복 억제(선택지 c).**
- **회전 시 `minRequiredDeposit`(40×) 강제.** 정상적으로 매매 가능한 소액 사이클까지 멈추므로 하지 않는다. 회전·재개의 기준은 1주 가격이다.
- **INFINITE 원장 증액 기능.**
- **API·UI의 PAUSED 사유 필드 신설.**
- **PRIVACY 등록 서버 검증.** 이 전략은 FIDA 기준표 조회가 별도로 필요하다. UI 가드가 있고, 중간부터 시작은 자체 배수 검증이 있다.

## 안티골

- 좀비 사이클 재발: ACTIVE 전략인데 열린 사이클이 없는 상태. 회전 가드는 반드시 PAUSED로 끝나야 한다.
- 증권사 시세 조회 장애로 정상 등록이 막히는 것. 그래서 등록 검증은 fail-open으로 한다.
- 기존 알림 문구·이벤트 상수명 변경(EPR `event_type` FQCN).

## 결정 사항

### 1. 회전 가드 (`CycleRotationService.rotate`)

- 현행 `targetSeed.signum() <= 0` 분기의 조건을 다음으로 바꾼다.
  - `price != null`이면 `targetSeed < price`
  - `price == null`이면 기존 `signum() <= 0`
  - 가격은 양수이므로 기존 ≤0 경우도 그대로 포함된다.
- 분기 동작은 현행 그대로 유지한다: `strategyPort.pause` → 관리자 `InsufficientBalanceEvent(userId=null)` → return.
- 사용자 알림(아래 3번)을 추가로 발행한다.
- `price`는 `CyclePositionPersistor`가 넘기는 당일 종가다. 다음 배치 0회차 매수①이 쓰는 전일종가와 같은 값이다.
- 기준을 1주로 정한 이유:
  - 교착 경계가 정확히 `원장 < 전일종가 × 1주`다(재현 표).
  - 1주 이상이면 매수가 들어가고, 다음 날 `isFinalRound`로 리버스에 진입해 사이클이 진행된다.
  - 타입 분기 없이 INFINITE·PRIVACY 공통이다. 1주도 못 사는 시드는 어느 전략에서도 진행할 수 없다.
- 로그 문구는 "원장 소진"을 "원장 1주 미만"으로 일반화한다.

### 2. 재개 가드 (`StrategyService.reopenCycleIfEnded`)

- 새 사이클 시드는 `latestCycle.endAmount()`, 가격은 최신 포지션의 `closingPrice`다(현행 그대로).
- `closingPrice != null && endAmount < closingPrice`이면 `IllegalStateException`을 던진다.
  - 문구 예: "남은 원장($12.34)이 1주 가격($60.00)보다 작아 재개할 수 없습니다. 전략을 삭제한 뒤 충분한 시드로 다시 등록해 주세요."
  - `ProblemDetailMappings`에서 400으로 매핑되고, detail이 그대로 노출된다.
- 예외는 사이클 생성·상태 저장 **전에** 던진다. 그래서 부분 변경이 없다.
- 회전 가드로 멈춘 전략, NONE 정책으로 멈춘 전략, 수동 체결 보정으로 보유가 0이 되어 멈춘 전략 모두 이 경로로 재개하므로 공통으로 적용된다.
- 관리자 재개(`AdminStrategyService#resumeStrategy`, 내부 API 경유)도 같은 메서드를 타므로 같이 거부된다. root 어댑터는 status만 보므로 관리자 화면에는 일반 400 문구가 나올 수 있다. 이것은 수용한다.

### 3. 자동 PAUSED 사용자 알림

- 신규 이벤트 `StrategyAutoPausedEvent`를 둔다.
  - 위치: `trading.application.event`
  - 필드: userId, accountId, accountNickname, strategyType, ticker, seed, price
- 발행: 회전 가드 분기(1번)에서 발행한다. `userProfile.userId()`를 쓴다.
- 구독:
  - `tradingcore.notify`의 `TradingAlertNotifier`에 리스너 메서드를 추가한다(`@TransactionalEventListener(fallbackExecution = true)`, 기존 패턴).
  - `TradingUserNotificationPort.notifyStrategyAutoPaused(...)`를 신설하고, `TradingUserNotificationAdapter`에 문구를 추가한다.
- 문구 예: "⏸️ **전략 자동 일시정지** — {계좌} [{타입}] {종목}: 다음 사이클 시드 $X가 1주 가격 $Y보다 작아 새 사이클을 열지 않았습니다. 재개해도 매수할 수 없으니 전략을 삭제하고 충분한 시드로 다시 등록해 주세요."
- 관리자 알림(`InsufficientBalanceEvent(userId=null)`)은 그대로 유지한다.
- 사용자는 같은 배치에서 `CycleCompletedEvent`("사이클 종료 — 다음 사이클 정책: MAX") 다음에 이 알림을 받는다.

### 4. 등록 서버 검증 (`StrategyCreationService.register`)

- 대상: `cmd.type() == INFINITE`
- 비교: `startAmount`(예수금 + 시장가 × 초기 보유)와 `minRequiredDeposit(prevClose, null, divisionCount)`를 비교한다. UI의 "예수금 + 보유 평가금 < minSeed"와 같은 판정이다.
- 가격 조회:
  - 중간부터 시작이면 이미 조회한 `marketPrice`를 재사용한다.
  - 신규 시작(holdings=0)이면 `brokerPricePort.getPrevClose`를 1회 조회한다.
  - 조회가 실패하면 **검증을 건너뛰고** warn 로그만 남긴다(fail-open). 이 검증은 UI 가드의 방어선이고, 시세 장애로 등록이 막히는 것이 더 나쁘기 때문이다.
- 미달이면 `IllegalArgumentException("최소 시드 $X 이상이 필요합니다.")`을 던진다(400). 문구는 UI의 `strategyFormGuards` 문구와 같다.
- 위치: 검증 블록(중복 종목·사용자 복제본 확인 뒤, 저장 전)
- `StrategyHistoryQueryService.strategySeedPreview` 주석의 "register()의 minRequiredDeposit 계산과 동일 경로"는 지금 사실이 아니다(등록은 이 값을 계산하지 않는다). 이번 변경으로 사실이 된다.

## 영향

- **kista-ui:** 변경이 없다.
  - 재개 실패 토스트는 서버 detail을 표시한다(flows.yml `pause-stop/request` fail 서술).
  - 등록은 폼이 같은 기준으로 먼저 막는다.
  - PAUSED 사유를 화면에 표시하는 것은 비목표다.
- **알림:** 사용자 알림 1종이 추가된다(자동 일시정지). 기존 알림은 변경하지 않는다.
- **flows.yml:**
  - `close-batch/rotate` 단계 desc에 "다음 시드가 1주 가격 미만이면 새 사이클 없이 PAUSED + 사용자 알림"을 추가한다.
  - `strategy` 생명주기의 "사이클 종료 후 다음 사이클을 열지 않음" desc에 원장 1주 미만 조건을 추가한다.
  - `pause-stop` 흐름의 resume 단계 fail에 "원장이 1주 가격 미만이면 400"을 추가한다.
  - `strategy-register` 흐름의 검증 단계 fail에 INFINITE 최소 시드 400을 추가한다.
- **문서:**
  - `docs/agents/modules/tradingcore/notify.md`, `trading.md`: 이벤트 수와 알림 목록에 신규 이벤트를 반영한다.
  - "trading 이벤트 12종" 같은 개수 서술이 있는 곳(architecture.md 모듈 표 등)을 grep해서 갱신한다.
- **EPR:** 신규 이벤트라서 고아 row 위험이 없다.
- **배포:** kista-trading만 바뀐다. 마이그레이션은 없다.

## 테스트 (최소)

- **`CycleRotationServiceTest`**
  - targetSeed < price이면 다음을 검증한다: pause 호출, 사이클 생성 없음, 관리자 이벤트와 `StrategyAutoPausedEvent` 발행, `NewCycleStartedEvent` 없음.
  - targetSeed == price이면 기존대로 재등록한다(경계).
  - price가 null이고 targetSeed > 0이면 기존대로 재등록한다.
- **`StrategyServiceTest`(resume)**
  - endAmount < closingPrice이면 `IllegalStateException`을 던지고, 사이클 생성과 상태 저장이 없다.
  - endAmount ≥ closingPrice이면 기존대로 재오픈한다.
- **`StrategyCreationServiceTest`**
  - INFINITE 시작 금액이 최소 시드 미만이면 400 예외를 던지고 저장하지 않는다.
  - 가격 조회 예외가 나면 검증을 건너뛰고 등록한다.
  - VR·DCA는 가격을 조회하지 않는다(현행 유지).
- **알림 어댑터 문구 단위 테스트:** 기존 `TradingUserNotificationAdapterTest` 패턴을 따른다.

## 근거 (재현, SOXL · 20분할 · 전일종가 60)

| 원장 | BUY | 총액 | fit |
|---|---|---|---|
| 50 | 매수① 1@60 | 60 | 거절 |
| 60 | 매수① 1@60 | 60 | 통과 |
| 100 | 매수① 1@60 + 보정 3 | 65.41 | 통과 |

원장 50, 종가 60에서 cap 경로:

- 현재가 58(cap 60.90) → 거절
- 현재가 48(cap 50.40) → 거절
- 현재가 45(cap 47.25) → 통과

## 빌더 재량

- 이벤트·포트 메서드의 정확한 이름과 알림 문구의 세부 표현
- 회전 가드에서 기존 로그·관리자 이벤트 인자(현재 `0, targetSeed`)를 그대로 쓸지
- 등록 검증 헬퍼를 private 메서드로 뺄지 여부
