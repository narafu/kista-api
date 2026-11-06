# 투자금 이력·장부가 실현손익 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 투자금 추가·인출 이력을 남겨 과거 원금선·자산선이 소급 변경되지 않게 하고, 요약 실현손익에서 VR 승계 평가익을 분리한다.

**Architecture:** trading-core에 `cycle_capital_event` 테이블과 포트를 추가하고 `CapitalInjectionService`가 같은 트랜잭션에서 기록한다. stats는 순수 헬퍼 `CapitalLedgerTimeline`로 이벤트를 소비해 원금 시계열·갱신 전 스냅샷 복원·장부가 실현손익을 계산한다. 스냅샷 제자리 갱신과 체결 기록 경로는 바꾸지 않는다.

**Tech Stack:** Java 25, Spring Boot 4, JPA/Hibernate, Flyway(`db/migration-trading`), JUnit 5 + Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-06-capital-ledger-history-design.md`

## Global Constraints

- 모든 파일은 `:trading-core`(`trading-core/src/...`), 패키지 루트 `com.kista.tradingcore`.
- 주석은 `//` 인라인만(Javadoc·블록 주석 금지), 신규 필드는 역할 한 줄 주석.
- 엔티티는 `@Table(schema = "trading")`, UUID PK는 `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)`, 저장 시 도메인 id는 null. `*JpaRepository`는 package-private.
- enum 컬럼은 `VARCHAR(20)` + `@Enumerated(EnumType.STRING)`.
- 현재 시각은 주입된 `Clock`(`Instant.now(clock)`), 날짜 버킷은 `atZone(TimeZones.KST).toLocalDate()`.
- application·domain IAE/ISE 메시지는 "~니다."로 끝난다.
- 테스트는 관련 클래스만 실행: `./gradlew :trading-core:test --tests '<FQCN>'`. 전체 스위트는 마지막 1회. postgres 컨테이너 필요(`docker compose up -d postgres`).
- 커밋 author `narafu <narafu@kakao.com>`, 한글 Conventional Commit, 끝에 아래 두 줄:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_014bowDDFmryE2rJc3Ac7yzm
  ```
- 서브에이전트가 import 수정 시 BOM 삽입 주의(`.claude/rules/java-encoding.md`).

## Review Focus

1. 같은 스냅샷 행에 이벤트 2건(추가 후 같은 날 인출) — 갱신 전 원본 복원은 두 delta를 모두 빼고, 각 이벤트 시각의 가상 스냅샷은 그 시점 값이어야 한다. → Task 3 `restore_twoEventsOnSameRow`.
2. 이벤트 시각이 UTC로는 전날, KST로는 다음 날(예: 15:30Z) — 원금 버킷은 KST 일자. → Task 3 `principalDeltaAfter_bucketsByKst`.
3. 인출(음수 delta) — 복원 시 부호가 뒤집혀 원본 예수금이 커져야 한다. → Task 3 `restore_withdrawal`.
4. equity-curve `from`이 이벤트 이전 날짜 — 범위 안 과거 원금이 이벤트 전 값. → Task 4 `equityCurve_capitalInjection_keepsPastPrincipalAndAsset`의 06-10 점.
5. 보유 승계 종료인데 avgPrice null인 스냅샷 — 장부가에서 평단 0 처리(NPE 금지). → Task 3 `bookValue_nullAvgPrice_isDeposit`.

---

### Task 1: 이벤트 테이블·도메인·포트·영속 어댑터

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V15__cycle_capital_event.sql`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/CycleCapitalEvent.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/port/output/CycleCapitalEventPort.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/out/persistence/CycleCapitalEventEntity.java`
- Create: `.../adapter/out/persistence/CycleCapitalEventJpaRepository.java`
- Create: `.../adapter/out/persistence/CycleCapitalEventPersistenceAdapter.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/out/persistence/CycleCapitalEventPersistenceAdapterTest.java`

**Interfaces:**
- Produces:
  - `record CycleCapitalEvent(UUID id, UUID strategyCycleId, UUID cyclePositionId, Kind kind, BigDecimal depositDelta, BigDecimal holdingsDelta, BigDecimal principalDelta, Instant occurredAt)` + nested `enum Kind { INJECT, WITHDRAW }`.
  - `interface CycleCapitalEventPort { CycleCapitalEvent save(CycleCapitalEvent event); List<CycleCapitalEvent> findByCycleIds(Collection<UUID> cycleIds); boolean existsByCycleId(UUID cycleId); }` — `findByCycleIds`는 `occurredAt` 오름차순, 빈 입력이면 DB 조회 없이 빈 목록.

- [ ] **Step 1: 마이그레이션 작성** (`flyway-migration` skill 규칙 준수)

```sql
CREATE TABLE trading.cycle_capital_event (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    strategy_cycle_id UUID NOT NULL,
    cycle_position_id UUID NOT NULL,
    kind              VARCHAR(20) NOT NULL,
    deposit_delta     NUMERIC(20,2) NOT NULL,
    holdings_delta    NUMERIC(20,8) NOT NULL,
    principal_delta   NUMERIC(20,2) NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_cycle_capital_event_strategy_cycle FOREIGN KEY (strategy_cycle_id)
        REFERENCES trading.strategy_cycle(id) ON DELETE CASCADE,
    CONSTRAINT fk_cycle_capital_event_cycle_position FOREIGN KEY (cycle_position_id)
        REFERENCES trading.cycle_position(id) ON DELETE CASCADE
);
CREATE INDEX idx_cycle_capital_event_strategy_cycle_id ON trading.cycle_capital_event (strategy_cycle_id);
```

- [ ] **Step 2: 실패 테스트 작성** — `StrategyCyclePersistenceAdapterTest`와 같은 패턴(`extends DataJpaTestBase`, `@Import(CycleCapitalEventPersistenceAdapter.class)`, `@Execution(SAME_THREAD)`, `JdbcTemplate`로 accounts → strategy → strategy_version → strategy_cycle → cycle_position 선행 행 삽입).
  - `save_thenFindByCycleIds_returnsInOccurredOrder`: 같은 사이클에 occurredAt 늦은 것·이른 것 순으로 2건 save → `findByCycleIds(Set.of(cycleId))`가 이른 것 먼저, 필드(kind·deltas·cyclePositionId) 왕복 일치, id non-null.
  - `findByCycleIds_emptyInput_returnsEmpty`.
  - `existsByCycleId`: 저장 전 false, 저장 후 true.

- [ ] **Step 3: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.out.persistence.CycleCapitalEventPersistenceAdapterTest'` → 컴파일 실패.

- [ ] **Step 4: 구현** — 엔티티는 `BaseCreatedAtEntity` 상속, 소프트 삭제·`@SQLRestriction` 없음. 매퍼는 필드 복사만. JpaRepository: `findByStrategyCycleIdInOrderByOccurredAtAsc(Collection<UUID>)`, `existsByStrategyCycleId(UUID)`.

- [ ] **Step 5: 통과 확인** — 같은 명령 PASS. `TradingApplicationContextLoadTest`도 실행해 `ddl-auto: validate` 통과 확인.

- [ ] **Step 6: Commit** — `feat(trading): 투자금 추가·인출 이력 테이블과 포트를 추가한다`

---

### Task 2: 투자금 추가·인출 기록 + 시드 수정 거절

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/CapitalInjectionService.java` (`inject` 105-113, `withdraw` 170-178)
- Modify: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/StrategyService.java` (`updateSeed` 206-220)
- Test: `.../application/service/CapitalInjectionServiceTest.java`, `.../application/service/StrategyServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `CycleCapitalEventPort`, `CycleCapitalEvent`.

- [ ] **Step 1: 실패 테스트 작성** (두 테스트 클래스에 `@Mock CycleCapitalEventPort cycleCapitalEventPort` 추가, `ArgumentCaptor<CycleCapitalEvent>`)
  - `CapitalInjectionServiceTest.inject_depositAndShares_recordsEvent`: 최신 포지션(id=P) holdings 10·avg 100·deposit 1,000, 요청 deposit 500 + shares 5 @ 120 → 이벤트 `(cycleId, P, INJECT, depositDelta 500, holdingsDelta 5, principalDelta 1100, occurredAt = Instant.now(FIXED clock))`.
  - `inject_depositOnly_recordsZeroHoldingsDelta`: holdingsDelta 0, principalDelta = deposit.
  - `withdraw_recordsNegativeDeltas`: amount 300 → `(WITHDRAW, -300, 0, -300)`.
  - 기존 거절 경로 테스트(초과 인출 등)에 `verify(cycleCapitalEventPort, never()).save(any())` 1곳 추가.
  - `StrategyServiceTest.update_newSeed_rejectedWhenCapitalEventsExist`: `existsByCycleId(cycleId)=true` → IAE 메시지 정확히 "투자금 추가·인출 이력이 있는 사이클은 예수금을 수정할 수 없습니다. 투자금 추가·인출을 이용해야 합니다.", `updateStartAmount` 미호출.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.CapitalInjectionServiceTest' --tests 'com.kista.tradingcore.trading.application.service.StrategyServiceTest'`

- [ ] **Step 3: 구현**
  - `CapitalInjectionService`: `updateBalance`·`updateStartAmount` 직후 같은 트랜잭션에서 `cycleCapitalEventPort.save(new CycleCapitalEvent(null, cycle.id(), latest.id(), kind, depositDelta, holdingsDelta, principalDelta, Instant.now(clock)))`. inject: `(deposit, sharesBd, deposit.add(injectedCost))`, withdraw: `(amount.negate(), ZERO, amount.negate())`.
  - `StrategyService.updateSeed`: holdings 검사 뒤 `if (cycleCapitalEventPort.existsByCycleId(cycle.id())) throw new IllegalArgumentException(위 문구)`.

- [ ] **Step 4: 통과 확인** — 같은 명령 PASS.

- [ ] **Step 5: 변이 확인** — `save` 호출을 잠시 지우고 `inject_depositAndShares_recordsEvent` 실패 확인 후 복구.

- [ ] **Step 6: Commit** — `feat(trading): 투자금 추가·인출 시 이력을 기록하고 이력 있는 사이클의 시드 수정을 거절한다`

---

### Task 3: 순수 헬퍼 `CapitalLedgerTimeline`

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/CapitalLedgerTimeline.java` (package-private final class, static 메서드만)
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/CapitalLedgerTimelineTest.java`

**Interfaces:**
- Consumes: Task 1 `CycleCapitalEvent`, trading `CyclePosition`.
- Produces (Task 4가 사용):
  - `static List<CyclePosition> restore(List<CyclePosition> positions, List<CycleCapitalEvent> events)` — 이벤트가 걸린 행 R을 갱신 전 원본(`usdDeposit − Σdeposit_delta`, `holdings − Σholdings_delta`, createdAt·id 유지)으로 바꾸고, 각 이벤트 e_k(occurredAt순)마다 `R − Σ_{j>k} delta` 값의 가상 스냅샷(`createdAt = e_k.occurredAt`, id·strategyCycleId·closingPrice·avgPrice는 R 값)을 추가한 뒤 `createdAt` 오름차순(안정 정렬)으로 반환. 이벤트가 걸리지 않은 행은 그대로. positions에 없는 행을 가리키는 이벤트는 무시.
  - `static BigDecimal principalDeltaAfter(List<CycleCapitalEvent> cycleEvents, LocalDate date)` — KST 일자 > date인 이벤트의 principalDelta 합.
  - `static BigDecimal principalDeltaTotal(List<CycleCapitalEvent> cycleEvents)`.
  - `static BigDecimal bookValue(CyclePosition p)` — `usdDeposit + holdings × (avgPrice ?: 0)`.
  - `static BigDecimal bookRealized(CyclePosition first, CyclePosition last, List<CycleCapitalEvent> cycleEvents)` — `bookValue(last) − (bookValue(first) − Σ(cyclePositionId == first.id인 이벤트 principalDelta)) − principalDeltaTotal(cycleEvents)`.

- [ ] **Step 1: 실패 테스트 작성**
  - `restore_singleInjection_splitsRowAtEventTime`: R(06-10, deposit 15,000) + 이벤트(06-20 01:00Z, +5,000) → [R'(06-10, 10,000), V(06-20 01:00Z, 15,000)].
  - `restore_twoEventsOnSameRow`: R(deposit 14,000, holdings 15) + e1(+5,000, holdings +5) 06-20, e2(−1,000) 06-21 → 원본 deposit 10,000·holdings 10, V1 deposit 15,000·holdings 15, V2 deposit 14,000·holdings 15.
  - `restore_withdrawal`: R(deposit 7,000) + 인출 −3,000 → 원본 10,000.
  - `restore_noEvents_returnsSameOrder`.
  - `principalDeltaAfter_bucketsByKst`: 이벤트 `2026-06-19T15:30:00Z`(KST 06-20) → `principalDeltaAfter(.., 06-19)` = delta, `(.., 06-20)` = 0.
  - `bookValue_nullAvgPrice_isDeposit`.
  - `bookRealized_vrRollover_onlySoldPart`: first(deposit 5,000, 50주, avg 100), last(deposit 6,200, 40주, avg 100) → 200.
  - `bookRealized_firstRowMutated_noDoubleCount`: first(id F, deposit 15,000, 0주) + 이벤트(F, principal 5,000), last(deposit 12,600, 30주, avg 100) → `15,600 − (15,000 − 5,000) − 5,000 = 600`.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.CapitalLedgerTimelineTest'`
- [ ] **Step 3: 구현** (`// ponytail:` 주석: 가상 스냅샷 avgPrice는 R 현재값 — 종가 없을 때 평단 대체 평가에만 쓰여 오차 허용)
- [ ] **Step 4: 통과 확인**
- [ ] **Step 5: Commit** — `feat(stats): 투자금 이력으로 스냅샷·원금·장부가를 복원하는 헬퍼를 추가한다`

---

### Task 4: `TradingStatsService` 소비 변경

**Files:**
- Modify: `trading-core/src/main/java/com/kista/tradingcore/stats/application/service/TradingStatsService.java` (생성자 55-63, `CycleView` 76-88, `loadCycles` 204-219, `computeEquityCurve` 155-170, `toTypeStats` 237-271, `buildPoints` 277-328, `chainAdjustment` 333-344)
- Test: `trading-core/src/test/java/com/kista/tradingcore/stats/application/service/TradingStatsServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `CycleCapitalEventPort.findByCycleIds`, Task 3 전 메서드.
- 생성자에 `CycleCapitalEventPort cycleCapitalEventPort`를 `cyclePositionPort` 다음에 추가(테스트 `setUp`도 갱신 — 기존 35개 테스트는 mock 기본값 빈 목록으로 그대로 통과해야 한다).

- [ ] **Step 1: 실패 테스트 작성**
  - `equityCurve_capitalInjection_keepsPastPrincipalAndAsset`: PRIVACY 진행 사이클 startAmount 15,000(현재), 포지션 P1(06-01, 10,000), P2(06-10, 15,000, 현금만), 이벤트(P2, +5,000/+5,000, 06-20 KST) → 점 06-01 (10,000/10,000), 06-10 (10,000/10,000), 06-20 (15,000/15,000).
  - `equityCurve_chainSurplus_usesStartAmountAtRotation`: 전략에 종료 사이클 C1(start 10,000, end 12,000) → 후속 C2(현재 startAmount 13,000, 회전 시점 11,000 + 이후 이벤트 +2,000). surplus = 12,000 − 11,000 = 1,000(기존 코드는 0). 회전일 이후 점의 자산·원금에 1,000 반영 단언.
  - `summary_vrRollover_splitsRealizedAndUnrealized`: VR 종료 C1(start 10,000, end 12,200, first deposit 5,000·50주·avg 100, last deposit 6,200·40주·avg 100), 진행 C2(start 12,200, latest deposit 6,200·40주·avg 100·close 160) → VR `realizedPnl` 200, `unrealizedPnl` 2,400, 합 2,600(기존 2,200 + 400).
  - `summary_vrReconfigureWithoutSell_realizedZero`: 종료 사이클 first·last 동일 보유(매도 없음) → realized 0, 합 보존.
  - `summary_liquidatedWithInitialHoldings_realizedIsEndMinusStart`: PRIVACY 종료 start 10,000(초기 100주 avg 60 close 80), last 0주 deposit 11,000, end 11,000 → realized 1,000, unrealized 0.
  - `cyclePerformances_unchangedForVrRollover`: 성과 목록 pnl은 end − start(2,200) 그대로.

- [ ] **Step 2: 실패 확인** — `./gradlew :trading-core:test --tests 'com.kista.tradingcore.stats.application.service.TradingStatsServiceTest'`

- [ ] **Step 3: 구현**
  - `CycleView`에 `List<CycleCapitalEvent> events` 추가, `principalOn(LocalDate d) = startAmount − principalDeltaAfter(events, d)`, `startAmountAtOpen() = startAmount − principalDeltaTotal(events)`. `loadCycles`에서 `findByCycleIds` 1회 조회 후 사이클별 그룹.
  - `computeEquityCurve`: `positions`를 `CapitalLedgerTimeline.restore(positions, 전체 이벤트)`로 바꾼 뒤 `buildPoints`.
  - `buildPoints`: 원금 합산을 `view.principalOn(date)`로.
  - `chainAdjustment`: `cs[i + 1].startAmount()` → 후속 `CycleView.startAmountAtOpen()`.
  - `toTypeStats`: 
    - `total = Σclosed(end − start) + Σactive unrealizedByCycle` (기존 두 값의 합)
    - 종료 사이클 실현: 마지막 스냅샷 holdings = 0이면 `end − start`, > 0이면 `bookRealized(first, last, events)`
    - `unrealizedPnl = total − realizedPnl`
    - 첫·마지막 스냅샷은 종료 사이클 id로 `findFirstByCycleIds`·`findLatestByCycleIds` 1회씩, 종료 사이클이 없으면 호출 생략. `computeSummary`에서 조회해 넘긴다.
    - 승률·평균 수익률은 기존 `CycleView.realizedPnl()`(구간 손익) 그대로.

- [ ] **Step 4: 통과 확인** — 같은 명령, 기존 35개 포함 PASS.
- [ ] **Step 5: 변이 확인** — (a) `principalOn` 대신 `principal()` 복귀 시 injection 테스트 실패, (b) bookRealized 분기 제거 시 VR 롤오버 테스트 실패 확인 후 복구.
- [ ] **Step 6: Commit** — `feat(stats): 투자금 이력으로 과거 원금·자산을 고정하고 요약 실현손익을 매도 확정분으로 바꾼다`

---

### Task 5: 문서·백로그·최종 검증

**Files:**
- Modify: `src/test/resources/architecture/flows.yml` (투자금 추가·인출 흐름, 통계 흐름)
- Modify: `docs/agents/modules/tradingcore/trading.md` ("진행 중 투자금 추가"·"인출" 항목에 이력 기록·시드 수정 거절)
- Modify: `docs/agents/modules/tradingcore/stats.md` ("DCA 포함" 원금 정의, "equity-curve 사이클 교체 체인 보정" surplus 정의, 실현손익 정의 1단락)
- Modify: `docs/agents/code-quality-backlog.md` (CQ-35 done, CQ-36 (c) done, CQ-33 체인 surplus 항목 done, 신규 CQ: PRIVACY(10-24) 원장·체결 86주·$1,232.44 불일치 조사)

- [ ] **Step 1: 문서 갱신** — 각 1~3줄, 위치·규칙만(코드가 SSOT).
- [ ] **Step 2: 가드·전체 검증** — `./gradlew :trading-core:test` 1회(ArchUnit·Modulith·`ArchitectureMapTest` 포함). 실패는 `grep -l 'failures="[1-9]' trading-core/build/test-results/test/TEST-*.xml`로 확인.
- [ ] **Step 3: Commit** — `docs: 투자금 이력·실현손익 정의를 문서와 백로그에 반영한다`

배포 후 후속(이 계획 범위 밖): kista-ui 활성 세션에 요약 "실현손익" 툴팁 요청, 계획·spec archive 이동.
