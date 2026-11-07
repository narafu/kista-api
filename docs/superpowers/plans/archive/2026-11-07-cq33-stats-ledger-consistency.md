# CQ-33 통계·원장 경계 정합성 묶음 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CQ-33의 남은 하위 항목을 닫는다. 코드 수정 4건(캐시 세대, 곡선 공백, 승률 본전 제외, 자본 변경 이벤트 3곳)과 문서화 3건을 처리한다.

**Architecture:** `:shared` `TtlResultCache`에 인스턴스 세대 카운터를 둔다. trading-core `TradingStatsService`의 곡선·승률 규칙을 고친다. `StrategyCapitalChangedEvent`를 발행하는 곳을 추가한다. 나머지는 주석과 문서다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5, Mockito, AssertJ

**Spec:** `docs/superpowers/specs/archive/2026-11-07-cq33-stats-ledger-consistency-design.md`

## Global Constraints
- 주석은 `//` 인라인만 쓰고 Javadoc은 금지한다. 신규 코드에는 주석을 함께 단다.
- 금액 자릿수는 기존 상수(`AccountBalance.LEDGER_SCALE` 등)를 쓴다. 승률은 기존대로 scale 4, `HALF_UP`이다.
- 테스트 명령에는 반드시 서브프로젝트를 지정하고(`:shared:test`, `:trading-core:test`) 로그를 거른다. 형식: `2>&1 | grep -E "FAILED|BUILD|ERROR|Test.*>"`
- 서브에이전트는 git add·commit을 하지 않고 파일만 수정한다(커밋은 마지막에 컨트롤러가 한 번만 한다).
- 커밋 메시지는 한글 Conventional Commit이고, author는 `narafu <narafu@kakao.com>`이다.

## Review Focus
1. 무효화가 loader 실행 중에 끼어들어도 loader 결과는 호출자에게 그대로 반환된다(버리지 않는다). 테스트: Task 1 `invalidationDuringLoad_returnsValueButDoesNotCache`
2. 후속 사이클이 soft delete 등으로 `cycles`에 없는 종료 사이클은 기존처럼 endDate 다음 날부터 곡선에서 빠진다. 테스트: Task 2 `endedCycleWithoutSuccessor_dropsAfterEndDate`
3. 공백 뒤 교체일에 곡선 자산·원금이 튀지 않는다. 테스트: Task 2 `delayedRollover_carriesOldCycleUntilSuccessorSnapshot`의 교체일 단언
4. 배포 전에 쌓인 `AccountDeletedEvent`처럼 `userId`가 null이어도 캐스케이드 삭제는 그대로 수행되고 이벤트만 건너뛴다. 테스트: Task 3 `onAccountDeleted_nullUserId_skipsCapitalEvent`
5. 이미 반영된 크립토 체결(`recordFill`이 false)에서는 자본 변경 이벤트를 발행하지 않는다. 테스트: Task 3 기존 중복 방지 테스트에 `never()` 단언 추가

---

### Task 1: TtlResultCache 세대 검사

**Files:**
- Modify: `shared/src/main/java/com/kista/shared/platform/cache/TtlResultCache.java`
- Test: `shared/src/test/java/com/kista/shared/platform/cache/TtlResultCacheTest.java`

**Interfaces:**
- Produces: 공개 API는 바뀌지 않는다(`getOrCompute`, `invalidateIf`, `peek`).

- [ ] **Step 1: 실패하는 테스트 작성**
```java
@Test
void invalidationDuringLoad_returnsValueButDoesNotCache() {
    AtomicInteger calls = new AtomicInteger();
    String first = cache.getOrCompute("k", Duration.ofMinutes(5), () -> {
        calls.incrementAndGet();
        cache.invalidateIf(_ -> true); // 계산 중 무효화
        return "old";
    });
    assertThat(first).isEqualTo("old");
    String second = cache.getOrCompute("k", Duration.ofMinutes(5), () -> { calls.incrementAndGet(); return "new"; });
    assertThat(second).isEqualTo("new");
    assertThat(calls).hasValue(2);
}
```
(`cache` 생성은 기존 테스트의 Clock 설정을 따른다.)
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :shared:test --tests 'com.kista.shared.platform.cache.TtlResultCacheTest' 2>&1 | grep -E "FAILED|BUILD|Test.*>"` → FAIL(두 번째 결과가 "old")
- [ ] **Step 3: 구현**
  - `private final AtomicLong generation = new AtomicLong();` 필드를 추가한다.
  - `invalidateIf`는 `removeIf` 전에 `generation.incrementAndGet()`을 호출한다.
  - `getOrCompute`는 `loader.get()` 직전에 `long gen = generation.get()`을 읽는다. put 직전에 `generation.get() != gen`이면 put하지 않고 `fresh`를 반환한다.
  - put 생략 지점에 `// ponytail: 인스턴스 전역 세대 — 무효화 순간 진행 중이던 다른 키 결과도 한 번 캐시 생략, 무효화가 잦아지면 키별 세대로` 주석을 단다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS(기존 테스트 포함)

---

### Task 2: 통계 곡선 공백 이어쓰기 + 승률 본전 제외

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/TradingStatsService.java`(`buildPoints` 약 L317-368, 승률 약 L280-283)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/domain/model/StrategyTypeStats.java:13`(주석)
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/TradingStatsServiceTest.java`

**Interfaces:**
- Produces: 공개 시그니처 변경은 없다.

- [ ] **Step 1: 실패하는 테스트 작성** (기존 테스트의 픽스처와 스텁 스타일을 따른다)
  - `delayedRollover_carriesOldCycleUntilSuccessorSnapshot`
    - 같은 전략에 사이클 A와 B가 있다.
    - A: startAmount 1000, 스냅샷 D-1(총자산 1100), endDate=D, endAmount 1100
    - B: startDate=D, startAmountAtOpen 1100, 첫 스냅샷 D+2(총자산 1100)
    - 단언: 곡선 D+1 포인트의 자산=1100.00, 원금=1000.00(A 원금)이다. D+2 포인트의 자산=1100.00, 원금=1000.00이다(교체일 연속).
  - `endedCycleWithoutSuccessor_dropsAfterEndDate`: 후속 사이클이 없는 종료 사이클 A(endDate=D)는 D+1 이후 포인트에서 빠진다(다른 진행 중 전략의 스냅샷으로 D+1 포인트를 만든다). 이 테스트는 지금도 통과할 수 있으며, 회귀 고정용이다.
  - `winRate_excludesBreakEvenCycles`: 같은 타입의 종료 사이클 손익이 +10, −10, 0이면 `winRate`=0.5000이다.
  - `winRate_nullWhenAllBreakEven`: 종료 사이클이 전부 손익 0이면 `winRate`=null이다.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.TradingStatsServiceTest' 2>&1 | grep -E "FAILED|BUILD|Test.*>"` → 공백 테스트·승률 테스트 FAIL
- [ ] **Step 3: `buildPoints` 구현**
  - 루프 전에 `Set<UUID> hasSuccessor`를 계산한다. `cycles`를 전략별로 묶고 `NEWER_CYCLE` 기준으로 정렬한 뒤, 가장 나중 사이클을 뺀 나머지 id를 넣는다.
  - "종료 사이클 탈락" 조건을 `endDate != null && date.isAfter(endDate) && !hasSuccessor.contains(view.cycle().id())`로 바꾼다.
  - 메서드 위 주석에 "롤오버·회전으로 후속이 있는 종료 사이클은 후속 스냅샷이 나타날 때까지 마지막 값을 이어 쓴다"를 추가한다.
- [ ] **Step 4: 승률 구현**
  - `wins`=`signum() > 0` 개수, `losses`=`signum() < 0` 개수로 센다.
  - `wins + losses > 0`일 때만 `wins / (wins + losses)`(scale 4, HALF_UP)를 계산하고, 아니면 null로 둔다.
  - `StrategyTypeStats.winRate` 주석을 `// 수익 사이클 비율 0~1 (본전 제외, scale 4)`로 바꾼다.
- [ ] **Step 5: 통과 확인** — Step 2 명령 → PASS(클래스 전체)

---

### Task 3: StrategyCapitalChangedEvent 미발행 3곳

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/event/AccountDeletedEvent.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/account/application/service/AccountDeletionWriter.java`(+ `delete` 호출처)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/event/AccountCascadeListener.java`
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyService.java`(`register` 약 L60)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CryptoOrderSynchronizer.java`(약 L123-125)
- Test: `AccountServiceTest`, `AccountCascadeListenerTest`, `StrategyServiceTest`, `CryptoOrderSynchronizerTest`(모두 trading-core test의 기존 파일)

**Interfaces:**
- Produces: `public record AccountDeletedEvent(UUID accountId, UUID userId) {}`. `userId`는 nullable이며, 배포 전 EPR에 쌓인 이벤트를 위한 것이다.
- Consumes: `StrategyCapitalChangedEvent(UUID userId)`(기존), `TradingAccount.userId()`

- [ ] **Step 1: 실패하는 테스트 작성**
  - `AccountServiceTest:302`의 단언을 `new AccountDeletedEvent(accountId, userId)`로 바꾼다(그 테스트의 소유자 id).
  - `AccountCascadeListenerTest`
    - `onAccountDeleted_publishesCapitalChanged`: `verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(userId))`
    - `onAccountDeleted_nullUserId_skipsCapitalEvent`: 삭제 포트 3개는 호출되고 `verify(eventPublisher, never()).publishEvent(any())`
  - `StrategyServiceTest`: register 테스트(없으면 추가)에 `verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID))`를 넣는다.
  - `CryptoOrderSynchronizerTest`
    - 체결 반영 테스트에 `verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(USER_ID))`를 추가한다.
    - `recordFill`이 false인 테스트에 `verify(eventPublisher, never()).publishEvent(any(StrategyCapitalChangedEvent.class))`를 추가한다.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :trading-core:test --tests '*AccountServiceTest' --tests '*AccountCascadeListenerTest' --tests '*StrategyServiceTest' --tests '*CryptoOrderSynchronizerTest' 2>&1 | grep -E "FAILED|BUILD|Test.*>"` → FAIL(컴파일 실패 포함)
- [ ] **Step 3: 구현**
  - **AccountDeletedEvent·AccountDeletionWriter:** 이벤트에 `userId` 필드를 추가한다. `AccountDeletionWriter.delete`가 `userId`를 받도록 파라미터를 추가하고, 호출처에서 이미 가지고 있는 소유자 id를 넘긴다.
  - **AccountCascadeListener:** `ApplicationEventPublisher`를 주입한다. 삭제 3건 뒤 `if (event.userId() != null) publish(new StrategyCapitalChangedEvent(event.userId()))`를 호출하고, null 분기에 `// 배포 전 EPR 잔여 이벤트 — userId 없음, 5분 TTL로 수렴` 주석을 단다.
  - **StrategyService.register:** `creationService.register(...)` 결과를 받아 둔 뒤 `eventPublisher.publishEvent(new StrategyCapitalChangedEvent(userId))`를 호출하고 결과를 반환한다.
  - **CryptoOrderSynchronizer:** `recordFill`이 true인 분기의 `CryptoOrderFilledEvent` 발행 옆에서 `new StrategyCapitalChangedEvent(ctx.account().userId())`를 발행한다.
- [ ] **Step 4: 통과 확인** — Step 2 명령 → PASS

---

### Task 4: 문서화 3건과 원장 갱신

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/VrCycleRolloverService.java`(약 L120-146, 주석만)
- Modify: `docs/agents/modules/tradingcore/privacy.md`
- Modify: `docs/agents/code-quality-backlog.md`(CQ-33 섹션만)
- Check: `src/test/resources/architecture/flows.yml`

- [ ] **Step 1:** VrCycleRolloverService의 `endAmount` 계산 직전에 주석을 단다. 문구: `// ponytail: 평가일 종가 × 실행일 매매 후 잔고 — 롤오버가 지연되면 그 사이 체결이 옛 사이클 평가에 섞인다. 정확히 하려면 평가일 이후 체결을 역산해 평가일 잔고를 재구성`
- [ ] **Step 2:** `privacy.md`에 "보유 초과·부족분 처리" 소절을 추가하고 spec 7번 내용을 적는다.
  - 동작: BUY로만 흡수한다. 부족분은 가장 싼 BUY에 더하고, 초과분은 가장 비싼 BUY부터 줄인다. BUY가 없으면 조정하지 않는다. SELL cap은 별도로 동작한다.
  - 의도 근거 3가지를 적는다.
  - 고정 테스트를 적는다: `PrivacyStrategyTest`의 `decrease*`, `noBuyTrades`, `sellCap*`.
- [ ] **Step 3:** backlog CQ-33의 각 하위 항목 끝에 결과를 표기한다.
  - 롤오버 지연 구멍, 본전 승률, TtlResultCache, 이벤트 미발행: `(해소 2026-11-07: <커밋>)`. 커밋 해시는 컨트롤러가 커밋 후 채운다. 우선 `(해소 2026-11-07)`만 적는다.
  - 종료금액 시점 혼합: `(문서화로 종결 2026-11-07: ponytail 주석)`
  - PRIVACY: `(문서화로 종결 2026-11-07: privacy.md)`
  - 수동 SELL: `(종결 2026-11-07: withdrawnOn 삭제(131b749b0)로 근거 소멸. 남은 한계 — 보정분은 곡선에 보정 실행일로 반영)`
  - 상태 줄을 `open(2026-11-04) → CQ-29 의존 항목만 남음(2026-11-07)`으로 바꾼다.
- [ ] **Step 4:** `grep -n "endDate\|종료 사이클\|승률\|winRate" src/test/resources/architecture/flows.yml`을 실행한다. 통계 곡선 규칙이나 승률을 설명하는 `desc`가 있으면 Task 2 동작에 맞게 고치고, 없으면 그대로 둔다.

---

### 최종 (컨트롤러)
- [ ] 전체 테스트 1회: `./gradlew test 2>&1 | grep -E "FAILED|BUILD"`
- [ ] `/code-review`로 검토하고, 실제 결함을 고친 뒤 재검증한다.
- [ ] 커밋 전에 `git diff --cached --stat`으로 범위를 확인하고 한 번에 커밋한다. 이어서 backlog에 커밋 해시를 기록하고, plan과 spec을 archive로 `git mv`해 두 번째 커밋을 만든다.
