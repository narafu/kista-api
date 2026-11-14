# 지침서 06. 배치 오케스트레이션 — 긴 메서드와 대기를 단계와 판정표로

> **실행 세션은 먼저 맨 끝의 11절 "실행 전 보정"과 `00-roadmap.md` 9절(공통 규칙)을 읽는다.** 본문과 다르면 그쪽이 우선한다. (2026-11-14)

작성 2026-11-12 (Fable). 근거 측정: `findings/03-parallel-structures.md` C·D절과 비일관 5번, `findings/05-frozen-decisions.md` S-25·C-02·C-30. 직접 읽은 코드(조기 마감 커밋 `9e542baf7`~`d3d78b0b3` 반영 상태): `TradingCloseScheduler`(239줄), `TradingOpenScheduler`(111), `TradingBatchResumer`(144), `TradingService`(485), `DstInfo`(161), `SessionTradeDates`, `KstTradeDates`, `MarketSessionTimes`, `CryptoDcaRunner`·`CryptoDcaScheduler`·`CryptoDcaRoundExecutor`.

> **2026-11-14 갱신**: 코인 매매를 전부 삭제한다(`15-crypto-removal.md`). 2-6절, T6, D2의 "코인 틱 핑은 그대로"는 대상이 없다. `CLOSE_REPORT` 창 시작은 `closeAt + 20분`으로 바뀐다(`14-market-data-provider.md` D3 — 무료 통합 시세가 15분 지연). 접수 직전 현재가 재조회(`reloadPlacementPrices`)는 T2·T3 뒤에 지운다(14의 T7).

**이 지침서는 실제 주문 경로를 바꾼다. 세 레포 지침서 중 위험이 가장 크다.** 단계마다 운영 배포 후 2거래일을 관찰하고 다음으로 간다.

## 1. 현재 구조와 문제

### 1-1. "언제 무엇을 실행할지"가 일곱 곳에 있다

| 위치 | 갖고 있는 판단 |
|---|---|
| `TradingCloseScheduler` | 거래일별 예약 맵, 접수 창 3종(`ORDER_MARGIN` 30분, `PLACEMENT_MARGIN` 10분, `MANUAL_WINDOW` 90분), 단계(phase)에 따른 분기(`runAutomatic`), 하트비트 대체 핑, 정지 전략 합류 |
| `TradingOpenScheduler` | cron 22:00, `canRun`(거래일·시각 경과 판정), 휴장일 핑, `LocalTime.of(4, 30)` 2회 |
| `TradingBatchResumer` | 요일·시각으로 저녁/자정 이후 구분, 마감·개장 재개 여부를 **스케줄러와 따로** 다시 판정 |
| `TradingService` | 체크포인트 기록, 주문 시각·마감 시각·개장 시각까지 `sleep`, 인터럽트 문구, 조기 반환마다 DONE 기록 |
| `DstInfo` | 시각 모델 두 벌: DST 고정 시각표(`calculate`, `immediateOpen`, `forOpenBatch`)와 실제 세션 기반(`forCloseSession`). 마감 30분 전·10분 후 값이 여기에도 있다 |
| `KstTradeDates`(shared) | 거래일 경계 = 04:30 고정 |
| `SessionTradeDates` → `MarketSessionTimes.nextTradeDate` | 거래일 경계 = 그날 실제 마감 30분 전 |

- 같은 값이 여러 번 적혀 있다: 04:30이 5곳(`DstInfo`, `KstTradeDates`, `TradingBatchResumer`, `TradingOpenScheduler` 2회), 22:00이 2곳, 마감 30분 전이 3곳(`TradingCloseScheduler.ORDER_MARGIN`, `DstInfo.forCloseSession`, `MarketSessionTimes.TRADE_DATE_SWITCH_MARGIN`).
- **거래일 경계 규칙이 호출처마다 다르다.** 미리보기·수동 실행·VR 재설정·DCA 요약은 세션 기반(`SessionTradeDates.nextTradeDate`), 개장 배치·개장 스케줄러·재개는 04:30 고정(`DstInfo.nextTradeDate`). 조기 마감일(마감 KST 02:00~03:00)의 02:30~04:30 구간에서 두 규칙이 다른 거래일을 답한다.
- 개장 배치는 DST 시각표(`forOpenBatch`)로, 마감 배치는 실제 세션으로 돈다.

### 1-2. 배치가 스레드를 잡고 잔다

- 개장: cron 22:00 → (KIS DCA 개장 전 접수) → 개장 시각까지 30분 또는 90분 `sleep` → 계획·접수.
- 마감: 예약 콜백(마감 30분 전) → 계획·접수 → 마감 10분 후까지 약 40분 `sleep` → 리포트.
- 그 사이 배포가 있으면 인터럽트 → 관리자 알림 → 기동 후 `TradingBatchResumer`가 체크포인트를 보고 이어간다.

체크포인트(`STARTED → PLANNED → PLACING → PLACED → DONE`)가 이미 있으므로 배치는 사실상 시각으로 구동되는 상태 기계다. 그런데 구현은 "잠을 포함한 긴 메서드 하나"와 "재개용 경로"의 두 벌이다.

- 리포트: 정상 경로는 메모리의 `placedStates`를 쓰고(`reportAll`), 재개·정지 전략은 DB에서 다시 조립한다(`rebuildPlacedStates` → `reportRebuilt`). 같은 일을 하는 두 경로다.
- 락 TTL이 2~3시간인 것도 잠 때문이다.
- `TradingService.executeBatch(contexts)`(주문 시각 대기 포함 경로)는 스케줄러가 더 이상 부르지 않는 것으로 보인다. 스케줄러는 `executeBatchNow`와 `resumeCloseReport`만 호출한다. 남은 호출처는 `TradingExecutionFacade.executeBatch`뿐이다(실행 세션이 grep으로 확정).

### 1-3. 스케줄러 3개가 같은 골격을 각자 갖는다

`serialExecutor.run` → `schedulerLockService.tryRun/takeOver` → `jobRunner.run(label, buildAll, useCase::x)` → 완료 시 `heartbeatPort.pingX()` → 실패 시 `errorReportPort`. 세 클래스 합 494줄이고 의존이 12·11·6개다.

### 1-4. DCA 실행 파이프라인이 둘이다

- 주식 DCA: `TradingService` 공통 배치(개장·마감), 입력 수집은 `DcaPlanInputCollector`(194줄).
- 코인 DCA: 10분 틱 `CryptoDcaScheduler` → `CryptoDcaRunner`(125) → `CryptoOrderSynchronizer`(135) → `CryptoDcaRoundExecutor`(242) → `CryptoLedgerRecorder`(63). 컨텍스트 조립을 `BatchContextFactory`와 같은 규칙으로 따로 한다(`CryptoDcaRunner` 주석이 직접 밝힌다).

## 2. 목표 구조

**원칙 셋. ① 시각 판단은 순수 함수 하나가 한다. ② 배치는 자지 않는다: 단계마다 예약되고, 단계는 DB에서 상태를 읽어 시작한다. ③ 정상 실행과 재개는 같은 코드다.**

### 2-1. 단계 3개

| 단계 | 발화 시각 | 하는 일 | 끝나면 기록 |
|---|---|---|---|
| `OPEN_PLACE` | 세션 `openAt` | 대상 확정 → 가격·기준표 조회 → 계획·예산 배정 → AT_OPEN 접수 | OPEN: DONE |
| `CLOSE_PLACE` | `closeAt − 30분` | 대상 확정 → 조회 → AT_CLOSE 계획·배정 → 캡 보정 → 접수 | CLOSE: PLACED |
| `CLOSE_REPORT` | `closeAt + 10분` | 당일 접수 주문을 DB에서 읽어 체결 조회·원장 기록·알림(지금의 `rebuildPlacedStates` 경로) | CLOSE: DONE |

- 단계 본문은 지금의 `TradingService` 코드에서 잠과 인터럽트 처리를 뺀 것이다. 계획기·실행기·리포터(`TradingCandidatePlanner`, `TradingOrderExecutor`, `TradingReporter` 등)는 그대로 쓴다.
- `CLOSE_REPORT`는 **항상** DB에서 다시 조립한다. 메모리 `placedStates` 경로(`reportAll`을 접수 직후 상태로 부르는 것)는 없앤다. 정지·보관 전략의 리포트도 같은 단계의 대상에 포함된다(지금의 `closeBatchStrategies`).
- 체크포인트의 `STARTED`·`PLANNED`는 "잠들기 전 어디까지 했나"를 위한 값이었다. 단계 모델에서는 `PLACING`(임계 구역 진입, 이중 접수 경고용), `PLACED`, `DONE`만 의미가 있다. 컬럼 값은 `VARCHAR`라 마이그레이션 없이 줄일 수 있지만, 한 배포 동안은 옛 값을 읽을 수 있게 둔다.
- 임계 구역(`runState.critical`)과 kista-trading의 stop-first·`stop_grace_period` ≥ 200s는 **그대로 필요하다**(접수 도중 종료 방지). 달라지는 것은 임계 구역 밖에서 배포해도 아무것도 끊기지 않는다는 점이다.

### 2-2. 판정표 (순수 함수)

```java
// calendar 모듈(지침서 05 단계 R) — Spring·DB·시계 의존 없음
record BatchStep(TradingBatchJob job, Kind kind, LocalDate tradeDate) { enum Kind { OPEN_PLACE, CLOSE_PLACE, CLOSE_REPORT } }

sealed interface Decision {
    record Run(boolean warnInterrupted) implements Decision {}   // warnInterrupted: 이전 phase가 PLACING
    record Reserve(Instant fireAt) implements Decision {}
    record Skip(String reason) implements Decision {}             // 휴장, 이미 완료
    record Missed(String alert) implements Decision {}            // 창 경과 — 관리자 알림
}

static Decision decide(BatchStep step, Optional<MarketSession> session, TradingBatchPhase phase, Instant now, Trigger trigger);
```

판정 규칙은 지금 코드의 동작을 옮긴 것이다. 새 규칙을 만들지 않는다.

| 단계 | 창 시작 | 창 끝 | 창 이전 | 창 안 | 창 이후 |
|---|---|---|---|---|---|
| `OPEN_PLACE` | `openAt` | `closeAt − 30분` | Reserve | Run | Missed "[개장 재개 누락]" |
| `CLOSE_PLACE` 자동 | `closeAt − 31분`(조기 발화 허용 1분) | `closeAt − 10분` | Reserve | Run | Missed "[재개 불가] 접수 마감 경과" |
| `CLOSE_PLACE` 수동 | `closeAt − 90분` | `closeAt − 10분` | Missed "[마감 접수 불가]" | Run | 같음 |
| `CLOSE_REPORT` | `closeAt + 10분`(14 D3 승인 시 `+ 20분`) | 그 거래일의 KST 자정 | Reserve | Run | Missed "[마감 리포트 누락] 거래일 경과" |

공통: 세션 없음 → Skip(휴장). phase가 그 단계의 완료 값 이상 → Skip. `CLOSE_REPORT`는 phase가 PLACED일 때만 Run. phase가 PLACING이면 `Run(warnInterrupted = true)`로 "[재개 경고] 접수 도중 중단 — 이중 접수 확인 필요" 알림을 낸다.

상수(30분·10분·90분·1분)는 이 클래스 한 곳에만 둔다.

### 2-3. 스케줄러 하나

```
TradingBatchScheduler (adapter/in/schedule, 약 120줄)
  @EventListener(ApplicationReadyEvent)  reconcile(alertIfMissed = true)   ← 지금의 TradingBatchResumer
  @Scheduled(매시 5분)                    reconcile(alertIfMissed = false)  ← 지금의 TradingCloseScheduler.reconcile
  runNow(step)                            관리자 수동 트리거

  reconcile: 오늘·내일 거래일의 단계 3개씩에 decide()를 적용
    Reserve → TaskScheduler 예약(같은 fireAt이면 그대로, 바뀌었으면 취소 후 재등록 — 지금 규칙 유지)
    Run     → serialExecutor → lock(tryRun, 기동 직후에는 takeOver) → jobRunner → 단계 실행
    Missed  → 관리자 알림(alertIfMissed일 때만)
```

- `TradingBatchResumer`의 요일·시각 분기는 사라진다. "지금 무엇을 해야 하나"는 (세션, phase, now)만으로 정해지기 때문이다. `@Profile("!local")`과 `@Order(LOWEST_PRECEDENCE)`(캘린더 기동 적재 뒤)는 유지한다.
- `TradingOpenScheduler`의 cron 22:00은 없어진다. 개장 단계도 캘린더의 `openAt`에 예약된다.
- 락 TTL은 단계 최대 소요 + 여유로 줄인다(15분에서 시작해 운영 로그의 단계 소요 시간을 보고 정한다).
- role: 지침서 05의 worker에서만 등록한다(`scheduler.enabled` → role 프로퍼티, 05 P4).

### 2-4. 시각 모델 하나

- 모든 시각은 캘린더의 `MarketSession(openAt, closeAt)`에서 나온다. `DstInfo`의 DST 고정 시각표(`calculate`, `immediateOpen`, `immediateClose`, `forOpenBatch`, `orderTime` 등)는 삭제한다.
- 거래일 경계는 `MarketSessionTimes.nextTradeDate`(세션 기반) 하나로 통일한다. `KstTradeDates`와 `DstInfo.nextTradeDate`를 지운다. 캘린더 미적재 시 fail-closed라는 성질 때문에 "취소·인출·보관"이 쓰는 `tradeDateCandidates`(오늘·내일 둘 다)는 유지한다.
- 수동 실행 차단 구간(`DstInfo.sessionAt`: 장마감 ~ 프리마켓 시작)도 세션에서 유도한다: 차단 = `[closeAt, 다음 세션 openAt − 5시간 30분)`. 평일 값은 지금과 같다(DST 05:00~17:00, 비DST 06:00~18:00 KST). 달라지는 것은 조기 마감일(차단이 실제 마감부터 시작)과 평일 휴장일(지금은 요일만 보고 DIRECT)이다. 둘 다 지금이 틀린 쪽이다.
- `TossPriceApi:161-199`의 세션 판정 복제는 지침서 04·05가 지운다. PROGRESS의 의심(조기 마감 미반영)은 그때 함께 해소된다.

### 2-5. 하트비트 한 곳

지금은 핑이 네 곳에서 나간다: 단계 완료, 휴장일(개장 스케줄러), 04:30 대체 핑(마감 스케줄러), 리포트 재개 완료. healthchecks.io 체크가 cron 일정(화~토 04:30 등)에 묶여 있어 "그 시각에 배치가 없는 날"을 코드가 메워 주는 구조다.

목표: **하루 한 번 "오늘 해야 할 단계 중 놓친 것이 없다"를 보낸다.** 매일 KST 07:30에 어제 저녁~오늘 아침 거래일의 단계 3개가 전부 DONE이거나 Skip(휴장)이면 핑을 보낸다. 판정은 2-2의 함수와 체크포인트 조회로 끝난다. 실패는 지금도 텔레그램 관리자 알림으로 즉시 나가므로, 하트비트는 "프로세스가 죽어 알림조차 못 보낸 경우"만 잡으면 된다.

이 변경은 healthchecks.io의 체크 일정을 "매일 07:30, grace 30분"으로 바꿔야 한다(**사용자 작업, D2**). 바꾸기 전에는 지금의 핑 위치를 유지한다.

### 2-6. DCA 파이프라인: 실행기는 둘로 두고 같은 부분만 합친다

**(2026-11-14 폐기 — 코인 실행기는 15에서 삭제한다. 아래는 기록용.)**

주식과 코인은 실행 모양이 다르다. 주식은 세션에 묶인 접수 → 마감 리포트이고, 코인은 24시간 시장에서 시장가 주문 → 폴링 동기화다. 실행기를 하나로 합치면 "세션이 있는가"를 묻는 분기가 실행기 안에 생긴다. **기각한다.**

합치는 것:

1. 컨텍스트 조립: `CryptoDcaRunner.buildContext`를 지우고 `BatchContextFactory`에 시장 필터(주식/코인)를 받는 메서드를 둔다. 실패 전략을 알림 후 제외하는 규칙이 이미 같다.
2. 규칙 입력 수집: `DcaPlanInputCollector`와 `CryptoDcaRoundExecutor.targets`·`closes`가 하는 일(실행 대상 규칙 고르기, 당일 이력 확인, 종가 열·지표 조회)의 대조표를 먼저 만든다. 같은 부분은 지침서 03의 `DcaKind` 쪽 한 곳으로 모으고, 시세 출처(브로커 시세 포트 / 거래소 포트)만 인자로 받는다. 대조 결과 겹침이 50줄 미만이면 합치지 않고 그 이유를 이 문서에 적는다.
3. 하루 1회 알림 제한(`CryptoDcaRunner.reportOncePerDay`, 메모리 맵): 그대로 둔다. 코인 틱에만 필요한 것이다.

업비트 삭제(지침서 04) 뒤 확인: `CryptoOrderSynchronizer.settle`의 "부분 체결 후 CANCEL이어도 FILLED 확정"(C-30)은 업비트 시장가 매수의 자투리 때문에 넣은 규칙이다. 바이낸스에서도 필요한지 스펙(`findings/12-binance-api-fit.md`)으로 확인하고, 필요 없으면 지운다.

### 기각한 대안

- **Spring Batch·Quartz 도입**: 단계 3개와 예약 6건(오늘·내일)이 전부다. 잡 저장소·재시작 의미론을 들여올 규모가 아니고, 체크포인트 테이블이 이미 그 역할을 한다.
- **cron을 유지하고 세션 시각만 보정**: 조기 마감 커밋이 마감 쪽에서 이미 cron을 버렸다. 개장만 cron으로 남으면 시각 모델 두 벌이 계속된다.
- **잠을 유지하고 재개 경로만 정리**: 정상·재개 두 벌이 남고 배포 때마다 인터럽트 알림이 난다.
- **개장·마감 리포트를 이벤트(Redis·EPR)로 연결**: 시각이 구동하는 흐름이다. 이벤트는 "앞 단계가 끝나면"을 표현하지 "10분 뒤"를 표현하지 못한다.

## 3. 사용자 결정이 필요한 것

| # | 내용 | 권고 |
|---|---|---|
| D1 | 개장 배치의 대상 전략 확정 시각. 지금은 22:00(cron 발화 때 `findAllActive`)이라 22:00~개장 사이에 등록·재개한 전략은 그날 밤 AT_OPEN 주문이 없다(`DstInfo.openBatchMissedAt`이 UI에 알림). 단계 모델에서는 `openAt`에 확정하는 것이 자연스럽다 | **`openAt`으로 옮긴다.** 사용자에게 유리한 방향의 동작 변경이다. `openBatchMissedAt`의 기준도 `openAt`으로 바꾼다. 22:00 유지를 원하면 `OPEN_PLACE`가 "22:00 시점 ACTIVE였던 전략"을 알 방법이 필요해 스냅샷 저장이 추가된다 |
| D2 | 하트비트를 하루 1회로(2-5). healthchecks.io 체크 일정 변경 필요 | 진행. 체크 3개(개장·마감·코인) 중 개장·마감을 하나로 합친다. 코인 틱 핑은 그대로 **(2026-11-14: 코인 체크는 15에서 삭제. 체크는 하나가 된다.)** |
| D3 | 조기 마감일·평일 휴장일의 수동 실행 차단 구간이 달라진다(2-4) | 진행. 지금 동작이 틀린 쪽 |

D1·D3은 실행을 막지 않는다. 결정 전에는 22:00 스냅샷 없이 `openAt` 확정으로 구현하되 배포 전에 사용자 확인을 받는다.

## 4. 재심한 과거 결정

| 결정 | 출처 | 재심 결과 |
|---|---|---|
| S-25 거래일 경계 04:30 고정(장 마감 이동·DST별 분기 기각) | `scheduler-time-table.md:63-69` | **이미 뒤집혀 있다.** 조기 마감 커밋이 세션 기반 경계를 넣었고 04:30 고정은 개장 쪽에만 남았다. 세션 기반으로 통일한다. 당시 기각 이유("바꿀 곳이 많다")는 2-4의 한 함수로 해소된다 |
| C-02 세션 계산 복제 유지 | backlog L583-585 | 뒤집는다(05에서 이미 판정). calendar 모듈로 |
| S-11 "매매 helper 세분화" 유지 | memory `project_2026_07_11_full_review.md` | **유지.** 계획기·실행기·캡퍼·리포터는 배치·수동·미리보기·DCA 수집 4갈래가 공유한다(`findings/03` C절). 단 `TradingExecutionFacade`(순수 위임)는 지침서 01 단계 D에서 지운다. 단계 모델 적용 뒤 호출처가 1곳이 된 헬퍼는 인라인한다(T7) |
| C-01·S-26 `TradingService:79,158`의 `LocalDate.now(KST)` 유지(체크포인트 키) | backlog L586-588 | 소멸. 단계가 `tradeDate`를 인자로 받는다 |
| C-37 재개 알림이 `e.getMessage()` 기반 | backlog L605 | 판정표의 `Missed.alert` 문구로 대체. 문구는 지금 것을 유지 |
| S-42 재발행 스케줄러 shared 추출 보류 | memory `project_review_refactor_2026_10_31.md` | 지침서 05가 EPR 한 벌로 정리하면서 소멸 |

## 5. 예상 감량과 위험

- main: 스케줄러 3개 494줄 → 약 150줄(스케줄러 120 + 실행 래퍼). `TradingService` 485줄 → 약 300줄(잠·인터럽트·이중 리포트 경로·조기 반환 체크포인트 제거, 단계 3개로 분리). `DstInfo` 161줄 → 삭제, 판정표 약 90줄 신설. `KstTradeDates` 삭제. 코인 컨텍스트 조립 약 50줄. **순감 약 600~750줄(추정).**
- test: `TradingCloseSchedulerTest`·`TradingOpenSchedulerTest`·`TradingBatchResumerTest`·`DstInfoTest`의 시각 시나리오가 판정표의 표 기반 테스트 하나로 모인다. `TradingServiceTest`(2,418줄)에서 잠 우회용 `DstInfo` 주입과 인터럽트 시나리오가 빠진다. 추정 1,000줄 이상 감소.
- 위험 1 (가장 큼): **리포트 경로 교체.** 정상 경로와 재조립 경로의 차이가 알려진 것 2건이다 — 재조립 경로는 기준표 screen을 적용하지 않고, 로테이션 최소금액 알림이 다르다(`TradingService` 주석). T2에서 차이를 전수 조사하고, 정상 경로에만 있던 동작은 재조립 경로로 옮긴 뒤 교체한다.
- 위험 2: 계획과 접수 사이에 메모리로 넘기던 상태(`CycleState`의 position·vrPosition·startPrice)는 `CLOSE_PLACE` 한 단계 안이라 유지된다. 개장도 같다. 단계 경계를 넘는 메모리 상태는 접수 → 리포트뿐이다.
- 위험 3: 예약 누락. 지금도 매시 `reconcile`과 기동 시 재개가 받치고 있다. 그대로 유지하고 하트비트(2-5)가 최종 방어다.
- 위험 4: KIS DCA 개장 전 접수(`placePreOpenDcaOrders`)가 남아 있으면 개장 단계가 둘로 갈린다. **지침서 04 단계 B(개장 경매 경로 삭제)가 선행**이다.
- 위험 5: 락. `takeOver`는 "이전 프로세스의 잔여 락 인수"다. 단일 worker 전제(05)에서만 안전하다. 전제가 바뀌면 이 지침서의 락 사용을 다시 본다.

## 6. 동작 보존용 선행 테스트

1. **판정표 테스트를 먼저 쓴다.** 기존 `TradingCloseSchedulerTest`·`TradingOpenSchedulerTest`·`TradingBatchResumerTest`의 시나리오(예약 시각 경과, 창 밖 수동 실행, PLACING 재개 경고, PLACED 리포트 재개, 거래일 불일치, 휴장일, 조기 마감일, 캘린더 갱신으로 마감 시각 변경)를 전부 (세션, phase, now, trigger) → Decision 행으로 옮긴다. 기존 테스트 하나당 행 하나가 대응해야 하고, 대응표를 PR 설명에 붙인다.
2. **리포트 동등성 IT**(`*IT`, 실 DB): 같은 접수 상태에서 정상 경로(`reportAll`)와 재조립 경로(`reportRebuilt`)가 만드는 `cycle_position`·`orders` 상태·발행 이벤트가 같은지 종류별(INFINITE 일반·리버스, VR 롤오버 있는 날, PRIVACY, 주식 DCA, 정지 전략)로 비교한다. 다르면 그 차이가 T2의 작업 목록이다.
3. **단계 멱등 IT**: 각 단계를 연속 두 번 실행해 주문·원장이 한 번만 반영되는지. `CLOSE_PLACE` 도중 예외 후 재실행 시 슬롯 멱등이 유지되는지.
4. 변이 확인: 판정표의 창 경계 하나를 틀리게 해 실패하는지, 리포트 대상 조회에서 정지 전략을 빼 실패하는지.

## 7. 태스크

공통 완료 판정: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.*' --tests 'com.kista.tradingcore.marketcalendar.*' 2>&1 | grep -E "FAILED|BUILD"` 통과, 해당 `*IT`는 `./gradlew integration 2>&1 | grep -E "FAILED|BUILD"`, 아키텍처 테스트 양쪽 통과. 배포는 매매 시간대 밖(KST 07:00~16:00).

| # | 내용 | 모델 | 배포·관찰 |
|---|---|---|---|
| T1 | 판정표(`BatchStep`, `Decision`, `decide`)와 6절 1번 테스트. 기존 코드는 아직 건드리지 않는다 | Opus | 없음 |
| T2 | 리포트 동등성 IT(6절 2번) → 차이를 재조립 경로로 흡수 → 마감 배치를 `CLOSE_PLACE`와 `CLOSE_REPORT`로 분리. 리포트는 스케줄러가 `closeAt + 10분`에 예약. 죽은 경로 `executeBatch(contexts)` 삭제 | Opus | 배포 후 2거래일. 리포트 시각·내용·알림을 전날과 대조 |
| T3 | (04 단계 B 뒤) 개장 배치를 `OPEN_PLACE` 단계로. cron 22:00 → `openAt` 예약. D1 확인 | Opus | 2거래일. AT_OPEN 주문 접수 시각·건수 대조 |
| T4 | 스케줄러 3개를 `TradingBatchScheduler` 하나로. `TradingBatchResumer` 삭제. 기존 스케줄러 테스트는 T1의 표가 대체하는지 대응표로 확인한 뒤 삭제 | Opus | 2거래일 + 장중이 아닌 시각에 배포 재기동 1회, 접수 후~리포트 전 재기동 1회를 **의도적으로** 해 본다 |
| T5 | 시각 모델 통일: `DstInfo`·`KstTradeDates` 삭제, 수동 실행 차단 구간을 세션 기반으로(D3), `TradingCycleResponse`의 `openBatchMissedAt` 기준 변경 | Opus | 1거래일 |
| T6 | **(2026-11-14 삭제 — 15)** 코인: `BatchContextFactory` 공용화, 입력 수집 대조표와 통합 여부 결정(2-6), C-30 확인 | Opus 판정, Sonnet 적용 | 코인 틱 1일 |
| T7 | 뒷정리: 체크포인트 값 `STARTED`·`PLANNED` 제거, 락 TTL 조정, 호출처 1곳이 된 헬퍼 인라인, 하트비트 하루 1회(D2 결정 뒤) | Sonnet | 1거래일 |
| T8 | 문서: `workflow-batch.md`·`scheduler-time-table.md`를 2-1·2-2의 표로 다시 쓰고(각 30줄 이하), `flows.yml`의 개장·마감 흐름과 `lifecycles`의 배치 phase 전이를 고친다. `docker-infra.md`의 재개 서술 | Sonnet | 없음 |

T2~T5는 각각 별도 커밋·별도 배포다. 한 배포에 두 태스크를 싣지 않는다.

## 8. 하지 말 것

- 계획·캡·예산 배정·접수·체결 기록의 **내용**을 바꾸지 않는다. 바꾸는 것은 "언제, 어떤 상태에서 시작하나"뿐이다.
- 임계 구역(`runState.critical`)과 `stop_grace_period`를 줄이지 않는다.
- 판정표에 지금 코드에 없는 규칙을 넣지 않는다(D1·D3 제외).
- 알림 문구를 바꾸지 않는다. 운영자가 문구로 상황을 구분한다.
- 단계 본문에 `Thread.sleep`·`runState.sleep`을 다시 넣지 않는다. 기다려야 하면 예약한다.
- 코인 실행기와 주식 배치를 한 클래스로 합치지 않는다.
- 미국장 정규 시간(KST 22:30~06:00)에 배포하지 않는다.

## 9. 중단 조건 (사용자에게 보고하고 멈춘다)

- 리포트 동등성 IT에서 원장 금액·보유 수량이 다르게 나오는데 원인을 설명할 수 없을 때.
- 배포 후 관찰에서 주문 건수·접수 시각·리포트 내용이 전날 패턴과 다를 때.
- `[재개 경고]`·`[마감 예약 누락]` 알림이 의도한 재기동 실험 밖에서 날 때.

## 10. 다른 지침서와의 관계

- 04(브로커): 단계 B(개장 경매 경로)가 T3의 선행. 업비트 삭제가 T6의 C-30 확인보다 먼저.
- 03(전략 축): `DcaKind`와 `afterPositionSaved`를 쓴다. 03 → 06 순서. T1·T2는 03 없이 시작할 수 있다.
- 05(통합): 스케줄러는 worker role에만. calendar 모듈(단계 R ②)이 판정표와 세션 타입의 집이다. R보다 먼저 하면 판정표를 `marketcalendar`에 두고 R에서 옮긴다. 단계 M(통합 전환)과 이 지침서의 T2~T4를 같은 주에 하지 않는다.
- 01(헥사고날): `TradingExecutionFacade`·`TradingExecutionUseCase` 제거가 먼저면 치환 범위가 준다.
- 07(테스트): `TradingServiceTest` 압축은 이 지침서 뒤.
- kista-infra: healthchecks 체크 일정(D2). 배포 파이프라인 변경은 없다.

## 11. 실행 전 보정 (2026-11-14 — 본문과 다르면 이 절이 우선)

공통 규칙은 `00-roadmap.md` 9절. 측정 원본은 `findings/23`. 사용자 결정 D1~D3은 전부 권고대로 확정됐다(`PROGRESS.md`). T6(코인)은 없다. `CLOSE_REPORT` 창의 시작은 14 T3이 `closeAt + 20분`으로 바꾼다. 06 T2는 `+10분`으로 만들고 14 T3이 상수 하나를 고치게 한다.

전제 정정:

- 22:00은 세 곳이다: `TradingOpenScheduler:32`(`CRON_TIME`), `:46`(cron 문자열), `DstInfo:121`(`OPEN_BATCH_FIRE_TIME`).
- `TradingService.executeBatch(contexts)`는 죽은 경로가 맞다(main 호출은 `TradingExecutionFacade:32,47`뿐이고 그 facade를 부르는 곳이 없다). 오버로드가 둘이다(`TradingService:63,75`).
- 락 TTL의 지금 값은 `TradingOpenScheduler:30`·`TradingCloseScheduler:55`의 `LOCK_TTL`에서 읽는다.
- T8의 "각 30줄 이하"는 목표다. 지금 `scheduler-time-table.md`는 71줄, `workflow-batch.md`는 34줄이다. 표 두 개로 다시 썼는데 30줄을 넘으면 넘긴 채로 둔다.
- `TossPriceApi`의 세션 판정 복제는 `:154-171`이고 14 T5가 파일째 지운다.

같은 커밋에서 고칠 가드:

- **T3**: `ArchitectureMapTest:31-36`이 `TradingOpenScheduler#run`의 실행 시각 `["22:00"]`을 단언한다. cron을 없애는 커밋에서 이 단언을 새 예약 방식에 맞게 고친다.
- **T2~T5**: `flows.yml`의 `code:` 참조(`TradingOpenScheduler#run`, `TradingCloseScheduler#schedule`·`#pingHeartbeatSlot`·`#runNow`, `TradingBatchResumer#recoverClose`·`#resumeOpen`, `DstInfo#waitUntil*`·`#openBatchMissedAt`, `TradingService#waitFor`)를 **지우거나 이름을 바꾸는 그 커밋에서** 고친다. T8로 미루면 중간 커밋의 `ArchitectureMapTest`가 깨진다. T8에는 흐름의 서술(`desc`·`cond`)과 `lifecycles`만 남긴다.
- **T4**: `GradleModuleBoundaryTest:74`와 `SchedulerDisabledContextTest`, `TradingSchedulerInternalController`가 부르는 `runNow` 두 개.
- T1의 판정표를 marketcalendar에 두면 그 타입이 marketcalendar의 공개 패키지(`domain`)에 있어야 trading이 쓸 수 있다.

T4의 재기동 실험과 8절의 배포 금지 시간이 겹친다. 이렇게 푼다:

- "장중이 아닌 시각의 재기동 1회"는 평소 배포로 충족한다.
- "접수 후~리포트 전 재기동 1회"는 미국장 정규 시간 안이라 8절의 예외다. **사용자가 날짜와 시각을 승인한 때에만** 한다. 승인이 없으면 하지 않고, 같은 상황을 만드는 IT(접수까지 끝난 체크포인트에서 `CLOSE_REPORT`만 다시 실행해 리포트가 한 번만 나가는지)로 대신한 뒤 T4의 완료 기록에 "운영 재기동 실험 미실시"를 적는다.

중단 조건의 대체 경로(9절에 더한다):

- 리포트 동등성 IT에서 원장이 다르다: T2의 분리를 커밋하지 않는다. 차이가 "정상 경로에만 있던 동작"(screen 적용, 로테이션 최소금액 알림)이면 본문대로 재조립 경로로 옮기고 다시 잰다. 설명되지 않는 차이면 T2를 멈추고 T1의 판정표만 남긴다. T3 이후는 T2에 기대므로 함께 멈춘다.
- 배포 뒤 관찰에서 주문 건수·접수 시각·리포트 내용이 전날과 다르다, 경고 알림이 실험 밖에서 난다: 사용자에게 이전 커밋으로 되돌리는 배포를 요청한다. 체크포인트 값 `STARTED`·`PLANNED`를 T7 전까지 읽을 수 있게 두는 이유가 이것이다. T7(옛 값 제거)은 T2~T5의 관찰이 전부 끝난 뒤에만 한다.

배포: T2~T5는 주문의 시각이 바뀌는 변경이다. 태스크마다 배포 전 확인표(로드맵 9-4)를 붙인다: 다음 거래일의 단계별 예정 시각(지금 대 변경 뒤), 진행 중 전략의 주문 비교. D2의 healthchecks.io 일정 변경은 사용자 작업이고, 바뀌기 전에는 핑 위치를 그대로 둔다.
