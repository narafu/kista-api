# 0회차 소액 시드 교착 방지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원장이 1주 가격보다 작은 0회차 교착을 막는다. 회전·재개·등록 입구에서 막고, 회전 자동 일시정지를 사용자에게 알린다.

**Architecture:**
- 회전(`CycleRotationService`): 기존 "원장 소진 → PAUSED" 분기의 기준을 `targetSeed < price`로 넓힌다.
- 재개(`StrategyService.reopenCycleIfEnded`): 같은 기준으로 거부한다.
- 신규 이벤트 `StrategyAutoPausedEvent`를 `tradingcore.notify`가 사용자 알림으로 보낸다.
- INFINITE 등록은 `minRequiredDeposit`를 fail-open으로 검증한다.
- 변경은 전부 trading-core이고 마이그레이션은 없다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit5 + Mockito, Spring Modulith 이벤트(EPR)

**Spec:** `docs/superpowers/specs/2026-10-25-infinite-small-seed-deadlock-design.md`

## Global Constraints

- `docs/agents/modules/tradingcore/trading-formulas.md`와 매매 공식(`InfinitePosition`·`InfiniteStrategy`)은 변경 금지
- 기존 이벤트 클래스명·패키지·알림 문구는 변경 금지(EPR `event_type` FQCN)
- 주석: `//` 인라인만 쓴다. Javadoc·블록 주석 금지. 신규 필드·분기마다 한 줄 주석
- 예외 detail은 사용자에게 그대로 보이는 한국어 완결 문장("~습니다.")
- 테스트는 서브프로젝트를 지정해 실행한다: `./gradlew :trading-core:test --tests '<FQCN>' 2>&1 | grep -E "FAILED|BUILD|Tests? "`
- 커밋 author는 `narafu <narafu@kakao.com>`, 메시지는 한글 Conventional Commit
- 커밋 끝 2줄: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01AMtdFkGkvn9DzS4KN1Yf4c`

## Review Focus

1. **회전 시 `price == null`:** 기존 `signum() <= 0` 판정으로 돌아가야 한다. 시드가 양수이면 재등록한다(Task 2 테스트).
2. **경계 `targetSeed == price`:** 1주를 살 수 있으므로 재등록해야 한다(Task 2 테스트).
3. **재개 시 최신 포지션 `closingPrice == null`:** 가드 없이 기존대로 재오픈해야 한다(Task 2 테스트).
4. **등록 시세 조회 예외:** 등록을 막지 않고 검증만 건너뛴다(Task 3 테스트).
5. **자동 일시정지 알림 리스너:** userId가 있는 이벤트를 받으면 사용자 알림만 보내고 관리자 notifyPort는 호출하지 않는다(Task 1 테스트).

---

### Task 1: `StrategyAutoPausedEvent`와 사용자 알림 경로

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/event/StrategyAutoPausedEvent.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/application/port/output/TradingUserNotificationPort.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingUserNotificationAdapter.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingAlertNotifier.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingUserNotificationAdapterTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/notify/adapter/out/gateway/TradingAlertNotifierTest.java`
- Docs: `docs/agents/modules/tradingcore/notify.md`, `docs/agents/modules/tradingcore/trading.md`, `docs/agents/architecture.md`
  - 이벤트 "12종" 서술을 실제 개수로 고친다. 현재 디렉토리는 이미 13개다(`CryptoOrderFilledEvent`가 목록에 없음). 이번에 추가하면 14개다.
  - trading.md의 이벤트 이름 목록에 `CryptoOrderFilled`·`StrategyAutoPaused`를 추가한다.

**Interfaces:**
- Produces:
  ```java
  public record StrategyAutoPausedEvent(UUID userId, UUID accountId, String accountNickname,
          StrategyType strategyType, StrategyTicker ticker, BigDecimal seed, BigDecimal price) {}
  // TradingUserNotificationPort
  void notifyStrategyAutoPaused(TradingUserProfile profile, String accountNickname, StrategyType strategyType,
                                StrategyTicker ticker, BigDecimal seed, BigDecimal price);
  ```

- [ ] **Step 1: 어댑터 문구 테스트 작성** (`notifyStrategyAutoPaused_sends_reason_and_action`, 기존 같은 파일의 `notifyNewCycleStarted` 테스트 패턴)
  - 입력: 계좌 `"메인"`, INFINITE, SOXL, seed `12.34`, price `60.00`
  - 단언: 전송 텍스트에 `"전략 자동 일시정지"`, `"메인"`, `"SOXL"`, `"$12.34"`, `"$60.00"`, `"삭제"`가 모두 포함된다.
- [ ] **Step 2: 리스너 테스트 작성** (`TradingAlertNotifierTest.onStrategyAutoPaused_notifies_user_only`)
  - profile을 stub한 상태에서 이벤트를 넣는다.
  - 단언: `userNotificationPort.notifyStrategyAutoPaused(profile, "메인", INFINITE, SOXL, seed, price)` 호출, `verifyNoInteractions(notifyPort)`
- [ ] **Step 3: 실패 확인**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.notify.adapter.out.gateway.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
  - Expected: 컴파일 실패(심볼 없음)
- [ ] **Step 4: 구현**
  - 이벤트 record를 만든다(필드마다 인라인 주석).
  - 포트 메서드를 추가한다.
  - 어댑터 문구는 아래 그대로 쓰고 `notify(profile, "전략 자동 일시정지", text)`로 보낸다:
    ```
    ⏸️ <b>전략 자동 일시정지</b> — %s%n[%s] %s 다음 사이클 시드 $%.2f가 1주 가격 $%.2f보다 작아 새 사이클을 열지 않았습니다.%n재개해도 매수할 수 없으니 전략을 삭제하고 충분한 시드로 다시 등록해 주세요.
    ```
    포맷 인자: escapeHtml(계좌), 타입 name, 종목 name, seed, price
  - `TradingAlertNotifier`에 `@TransactionalEventListener(fallbackExecution = true) onStrategyAutoPaused`를 추가한다. 동작은 `requireProfile`로 프로필을 가져온 뒤 포트를 호출하는 것이다. 클래스 상단 주석의 이벤트 개수도 갱신한다.
- [ ] **Step 5: 통과 확인** — Step 3과 같은 명령으로 실행하고 `BUILD SUCCESSFUL`이 나와야 한다.
- [ ] **Step 6: 문서 개수·목록 갱신 후 커밋** — `feat(notify): 전략 자동 일시정지 사용자 알림을 추가한다`

### Task 2: 회전·재개 1주 가드

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CycleRotationService.java:52-63`(원장 소진 분기)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyService.java:124-138`(`reopenCycleIfEnded`)
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/CycleRotationServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyServiceTest.java`
- Modify: `src/test/resources/architecture/flows.yml`
  - `close-batch` 흐름 `rotate` 단계 desc 끝에 다음 문장을 추가한다: "다음 사이클 시드가 1주 가격(당일 종가) 미만이면 새 사이클 없이 전략을 PAUSED로 두고 사용자에게 자동 일시정지를 알린다(관리자 알림 유지)."
  - `lifecycles.strategy`의 "사이클 종료 후 다음 사이클을 열지 않음" desc를 다음으로 바꾼다: "청산으로 사이클이 끝났을 때 연속 정책이 NONE이거나, 다음 시드가 1주 가격 미만이거나, 사이클 재등록이 실패하면 좀비 사이클을 막으려고 전략을 멈춘다."
  - `pause-stop` 흐름 `resume` 단계 fail 앞에 다음을 추가한다: "종료 사이클의 최종 금액이 1주 가격(최신 종가) 미만이면 400(삭제 후 재등록 안내), "

**Interfaces:**
- Consumes: Task 1 `StrategyAutoPausedEvent`
- Produces: 없음

- [ ] **Step 1: 회전 테스트 작성** (`CycleRotationServiceTest`, 기존 MAX 회전 테스트의 픽스처 재사용)
  - `rotate_pauses_when_seed_below_one_share`: 최신 포지션 usdDeposit `50`, MAX, price `60`
    - 단언: `strategyPort.pause(strategyId)` 호출
    - 단언: `cycleSnapshotCreator` 상호작용 없음
    - 단언: `InsufficientBalanceEvent` 발행
    - 단언: `StrategyAutoPausedEvent`가 발행되고 seed `50`, price `60`, userId = profile userId
    - 단언: `NewCycleStartedEvent` 미발행
  - `rotate_reopens_when_seed_equals_one_share_price`: usdDeposit `60`, price `60`
    - 단언: `createCycleAndSnapshot(..., 60, 60)` 호출, pause 없음
  - `rotate_with_null_price_keeps_positive_seed_rotation`: usdDeposit `50`, price `null`
    - 단언: `createCycleAndSnapshot` 호출, pause 없음
- [ ] **Step 2: 재개 테스트 작성** (`StrategyServiceTest`, 기존 `resume_reopens_ended_cycle_with_end_amount_as_seed` 픽스처 재사용)
  - `resume_rejects_when_end_amount_below_one_share`: endAmount `12.34`, 최신 포지션 closingPrice `60.00`
    - 단언: `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class).hasMessage("남은 원장($12.34)이 1주 가격($60.00)보다 작아 재개할 수 없습니다. 전략을 삭제한 뒤 충분한 시드로 다시 등록해 주세요.")`
    - 단언: `cycleSnapshotCreator`·`strategyPort.save` 상호작용 없음
  - `resume_reopens_when_closing_price_missing`: closingPrice가 null이면 기존대로 재오픈하고 ACTIVE로 저장한다.
- [ ] **Step 3: 실패 확인**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.CycleRotationServiceTest' --tests 'com.kista.tradingcore.trading.application.service.StrategyServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
  - Expected: 신규 테스트 FAILED
- [ ] **Step 4: 구현**
  - **회전 판정식:** `price != null ? targetSeed.compareTo(price) < 0 : targetSeed.signum() <= 0`
  - **회전 분기:** 기존 pause·관리자 이벤트 뒤에 `new StrategyAutoPausedEvent(userProfile.userId(), account.id(), account.nickname(), strategy.type(), strategy.ticker(), targetSeed, price)`를 발행한다.
    - 로그는 `"원장 1주 미만({} < {}) — 새 사이클 없이 PAUSED"`로 바꾼다.
    - 주석은 "1주도 못 사는 시드는 0회차 매수가 원장 상한에 막혀 교착"으로 쓴다.
  - **재개:** `reopenCycleIfEnded`에서 closingPrice를 조회한 직후, `createCycleAndSnapshot` 전에 판정한다.
    - `closingPrice != null && latestCycle.endAmount().compareTo(closingPrice) < 0`이면 위 문구로 `IllegalStateException`을 던진다.
    - 금액은 `%.2f` 포맷이다.
    - 지금은 activeVersion 조회가 closingPrice 조회보다 먼저다. 순서는 그대로 둬도 되고, 앞당겨도 된다.
- [ ] **Step 5: 통과 확인** — Step 3과 같은 명령으로 실행하고 `BUILD SUCCESSFUL`이 나와야 한다.
- [ ] **Step 6: flows.yml 갱신 후 `ArchitectureMapTest` 실행**
  - Run: `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD"`
  - Expected: `BUILD SUCCESSFUL`
- [ ] **Step 7: 커밋** — `feat(trading): 원장이 1주 가격 미만이면 사이클 회전·재개를 멈춘다`

### Task 3: INFINITE 등록 최소 시드 서버 검증

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyCreationService.java`
  - 생성자 필드 `CycleOrderStrategies cycleStrategies`를 추가한다.
  - 검증 위치는 `startAmount` 계산 직후, `creationPersister.persist` 전이다.
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/support/StrategyHistoryQueryService.java:53` — 주석은 이번 변경 후 사실이 되므로 그대로 둔다. 변경이 필요 없는지만 확인한다.
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/StrategyServiceTest.java` — `new StrategyCreationService(...)`(약 109행)에 `CycleOrderStrategies`를 넘긴다. 같은 테스트의 기존 전략 목록을 재사용한다.
- Modify: `src/test/resources/architecture/flows.yml` — `strategy-register` 흐름 `validate` 단계 fail 끝에 다음을 추가한다: " INFINITE는 시작 금액(예수금+보유 평가금)이 최소 시드(전일종가×분할×2) 미만이면 400 — 전일종가 조회가 실패하면 이 검증만 건너뛴다(폼이 같은 기준으로 먼저 막는다)."

**Interfaces:**
- Consumes: `CycleOrderStrategy.minRequiredDeposit(BigDecimal price, PrivacyPlan plan, int divisionCount)`(price가 null이면 null 반환)
- Produces: 없음

- [ ] **Step 1: 테스트 작성** (`StrategyServiceTest`, 기존 INFINITE 등록 테스트 픽스처 재사용)
  - `register_infinite_rejects_seed_below_min`: `getPrevClose` → `60`, 20분할, initialUsdDeposit `2000`(최소 2400)
    - 단언: `IllegalArgumentException`, 메시지 `"최소 시드 $2,400.00 이상이 필요합니다."`
    - 단언: `strategyPort.save` 등 저장 상호작용 없음
  - `register_infinite_accepts_seed_at_min`: deposit `2400` → 등록된다.
  - `register_infinite_skips_min_check_when_price_fetch_fails`: `getPrevClose`가 `RuntimeException`을 던지면 → 등록된다.
  - `register_vr_does_not_fetch_prev_close`: VR 신규 등록(보유 0) → `verify(brokerPricePort, never()).getPrevClose(any(), any())`
- [ ] **Step 2: 실패 확인**
  - Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.StrategyServiceTest' 2>&1 | grep -E "FAILED|BUILD"`
  - Expected: 신규 테스트 FAILED
- [ ] **Step 3: 구현** — `private void validateInfiniteMinSeed(Account account, StrategyTicker ticker, BigDecimal marketPrice, int divisionCount, BigDecimal startAmount)`
  - 호출 조건: `cmd.type() == StrategyType.INFINITE`
  - 가격: `marketPrice`(중간부터 시작이면 이미 조회됨)가 있으면 그대로 쓴다. 없으면 `brokerPricePort.getPrevClose`를 직접 호출한다.
    - `BrokerCallGuard`로 감싸지 않는다(fail-open이라 503 변환이 불필요하다).
    - 예외는 catch하고 `log.warn`을 남긴 뒤 return한다.
  - `min = cycleStrategies.of(INFINITE).minRequiredDeposit(price, null, divisionCount)`
  - `min != null && startAmount < min`이면 `IllegalArgumentException("최소 시드 $%s 이상이 필요합니다.".formatted(<min을 #,##0.00 포맷>))`을 던진다. 문구는 kista-ui `strategyFormGuards`(`최소 시드 $${fmtUsd(minSeed)} 이상이 필요합니다.`)와 같다.
- [ ] **Step 4: 통과 확인** — Step 2와 같은 명령으로 실행하고 `BUILD SUCCESSFUL`이 나와야 한다.
- [ ] **Step 5: flows.yml 갱신 후 `ArchitectureMapTest` 실행** — Task 2 Step 6과 같은 명령, `BUILD SUCCESSFUL`
- [ ] **Step 6: 커밋** — `feat(trading): INFINITE 등록 시 최소 시드를 서버에서 검증한다`

### 마무리 (컨트롤러)

- [ ] 전체 스위트 1회: `./gradlew :trading-core:test :test 2>&1 | grep -E "FAILED|BUILD|tests completed"`
- [ ] 커밋 전 검토자 검수(태스크별 리뷰로 충족하면 최종 전체 리뷰는 생략할지 사용자에게 확인)
- [ ] 메모리 `project_infinite_correction_followups.md` 2번을 "완료"로 갱신
