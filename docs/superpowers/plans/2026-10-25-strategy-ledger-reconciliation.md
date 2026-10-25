# 전략 원장 대사 알림 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 하루 1회 계좌별 실보유량과 전략 원장 holdings 합을 대조하고, 원장 음수를 검사해 괴리만 관리자 텔레그램으로 알린다.

**Architecture:** trading-core `com.kista.tradingcore.trading` 모듈 안에서 끝난다. 비교 규칙은 순수 도메인 클래스, 데이터 수집·알림은 application service, 발화는 `adapter/in/schedule` 스케쥴러(락 + cron)다. 알림은 기존 `TradingErrorReportPort`, 브로커 잔고는 기존 `PortfolioPort`를 쓴다.

**Tech Stack:** Java 25, Spring Boot 4, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-25-strategy-ledger-reconciliation-design.md`

## Global Constraints

- 매매 경로에 넣지 않는다 — 대사 결과는 주문 생성·원장 예산·전략 상태에 절대 반영하지 않는다(쓰기 0건).
- 정상이면 아무것도 보내지 않는다.
- 경보 조건(실계좌): 종목별 `브로커 holdings < 원장 holdings 합`만. `브로커 > 원장 합`은 무시.
- 불변식: 전략 최신 `usd_deposit < 0` 또는 `holdings < 0`이면 경보. 스펙은 MOCK만 명시했지만 실계좌에도 비용 없이 같은 검사를 적용한다(계획 단계 판단).
- 대상 계좌: `account.broker().getMarket().isCrypto() == false`. MOCK은 보유량 대조 없이 불변식만.
- 대상 전략: 계좌의 삭제되지 않은 전략 중 최신 사이클 `endDate() == null`. 전략 status는 가리지 않는다(PAUSED 포함).
- 브로커 보유량: `PortfolioPort.getPresentBalance(account.toBrokerRef()).items()`의 `Item.ticker()`·`Item.holdings()`(종목 없으면 0).
- 실행: 화~토 09:00 KST, 락 이름 `ledger-reconciliation`, TTL 30분, `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)`.
- 알림 메시지에 계좌번호 금지 — 계좌 nickname과 accountId만.
- 주석은 `//` 인라인만, Javadoc 금지(constraints.md 주석 규칙).

## Review Focus

1. 한 계좌의 `getPresentBalance` 예외 — 다른 계좌 검사는 계속되고, 그 계좌는 대사 경보와 구분되는 조회 실패 알림 1건. (Task 2 테스트)
2. 같은 종목 전략 2개(예: INFINITE + DCA SOXL)가 한 계좌에 있을 때 합산 — 한쪽만 보고 오탐하지 않는다. (Task 1 테스트)
3. 브로커 응답에 원장 종목이 아예 없음(전량 매도 후 원장만 남음) — 브로커 0으로 보고 경보. (Task 1 테스트)
4. 원장 holdings가 소수(코인이 아닌데 BigDecimal `5.00000000`) — `compareTo`로 비교해 scale 차이로 오탐하지 않는다. (Task 1 테스트)
5. 최신 사이클이 종료됐거나 cycle_position이 없는 전략 — 합산에서 빠지고 예외를 내지 않는다. (Task 2 테스트)

---

### Task 1: 순수 대사 규칙

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/reconciliation/LedgerReconciliation.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/reconciliation/LedgerReconciliationTest.java`

**Interfaces:**
- Produces:
  - `record LedgerReconciliation.StrategyLedger(UUID strategyId, StrategyType type, StrategyTicker ticker, BigDecimal usdDeposit, BigDecimal holdings)`
  - `record LedgerReconciliation.Finding(String message)` — 사람이 읽는 한 줄 문구
  - `static List<Finding> check(List<StrategyLedger> ledgers, Map<StrategyTicker, Integer> brokerHoldings)` — `brokerHoldings == null`이면(MOCK) 보유량 대조를 건너뛰고 불변식만 본다. 반환 순서: 불변식(전략 입력 순) → 보유량 부족(ticker `name()` 오름차순).
  - 문구 형식(고정):
    - 불변식: `"원장 음수: strategy=<id> <type> <ticker> usdDeposit=<v> holdings=<v>"`
    - 부족: `"실보유 부족: <ticker> 브로커=<n> < 원장합=<sum> (<type>:<holdings>, ...)"` — 내역은 입력 순서, holdings는 `stripTrailingZeros().toPlainString()`

- [ ] **Step 1: Write the failing test**

```java
class LedgerReconciliationTest {
    // ledger(type, ticker, deposit, holdings) 헬퍼로 StrategyLedger 생성(strategyId 랜덤)

    @Test void 정상이면_빈_목록() // SOXL 원장 10, 브로커 10 → isEmpty()
    @Test void 브로커가_더_많으면_무시() // 원장 10, 브로커 15 → isEmpty()
    @Test void 같은_종목_전략_합산_부족() // INFINITE SOXL 6 + DCA SOXL 4, 브로커 9
        // → 1건, message contains "실보유 부족: SOXL 브로커=9 < 원장합=10", "INFINITE:6", "DCA:4"
    @Test void 같은_종목_전략_합산_일치() // 위와 같고 브로커 10 → isEmpty()
    @Test void 브로커에_종목이_없으면_0으로_본다() // 원장 TQQQ 3, 브로커 Map.of() → "브로커=0 < 원장합=3"
    @Test void 원장_0주는_브로커_부재여도_정상() // 원장 SOXL 0, 브로커 Map.of() → isEmpty()
    @Test void 소수_scale_차이로_오탐하지_않는다() // 원장 new BigDecimal("5.00000000"), 브로커 5 → isEmpty()
    @Test void MOCK은_보유량_대조를_건너뛴다() // 원장 SOXL 10, brokerHoldings null → isEmpty()
    @Test void 음수_예수금은_MOCK에서도_경보() // deposit -126.71, null → 1건 contains "원장 음수", "usdDeposit=-126.71"
    @Test void 음수_holdings_경보() // holdings -1, 브로커 Map.of(SOXL, 0) → "원장 음수" 1건 포함
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.domain.model.reconciliation.*' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`LedgerReconciliation` 없음)

- [ ] **Step 3: Implement `LedgerReconciliation`** — final 유틸 클래스(private 생성자) + 위 두 nested record + `check`. ticker별 합산은 `Collectors.groupingBy(..., TreeMap::new by name)` 또는 LinkedHashMap 후 정렬 — 반환 순서 규칙만 지키면 된다. `shared.kernel`(StrategyType/StrategyTicker) 외 의존 금지(도메인 레이어).

- [ ] **Step 4: Run test to verify it passes** — Step 2 명령, Expected: `BUILD SUCCESSFUL`, FAILED 없음

- [ ] **Step 5: Commit**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/domain/model/reconciliation trading-core/src/test/java/com/kista/tradingcore/trading/domain/model/reconciliation
git commit -m "feat(trading): 전략 원장 대사 규칙을 추가한다"
```

---

### Task 2: 대사 서비스·스케쥴러·문서

**Files:**
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/LedgerReconciliationUseCase.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/application/service/LedgerReconciliationService.java`
- Create: `trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/LedgerReconciliationScheduler.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/application/service/LedgerReconciliationServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/schedule/LedgerReconciliationSchedulerTest.java`
- Modify: `docs/agents/scheduler-time-table.md` "거래 관련" 표, `docs/agents/modules/tradingcore/trading.md`, `src/test/resources/architecture/flows.yml`(ApiKeyExpiry 흐름 옆)

**Interfaces:**
- Consumes: Task 1 `LedgerReconciliation.check`, `StrategyLedger`, `Finding`
- Consumes(기존): `AccountPort.findAll()`, `StrategyPort.findByAccountIds(Collection<UUID>)`, `StrategyCyclePort.findLatestByStrategyIds(Collection<UUID>)`, `CyclePositionPort.findLatestByCycleIds(Collection<UUID>)`, `PortfolioPort.getPresentBalance(BrokerAccountRef)`, `TradingErrorReportPort.reportError(Exception)`, `SchedulerLockService.tryRun(String, Duration, LockedTask) throws InterruptedException`
- Produces: `interface LedgerReconciliationUseCase { void reconcileAll(); }`

- [ ] **Step 1: Write the failing service test** (`@ExtendWith(MockitoExtension.class)`, 포트 전부 `@Mock`, 계좌는 `TradingFixtures.kisAccount/tossAccount/upbitAccount(id, userId)` 사용 — MOCK 팩토리는 없으므로 Account 생성자나 기존 with 메서드로 broker만 MOCK인 계좌를 만든다)

```java
@Test void 괴리_계좌만_계좌별_1건_알림() // KIS 계좌 원장 SOXL 10·브로커 9, Toss 계좌 정상
    // → verify(errorReportPort, times(1)).reportError(captor); 메시지 contains "[원장 대사]", 계좌 nickname, "실보유 부족"
@Test void 정상이면_알림_없음() // verifyNoInteractions(errorReportPort)
@Test void MOCK은_브로커를_조회하지_않는다() // MOCK 계좌 → verify(portfolioPort, never()).getPresentBalance(any())
@Test void 코인_계좌는_건너뛴다() // UPBIT 계좌 → strategyPort·portfolioPort 조회 대상에서 제외, 알림 없음
@Test void 종료_사이클과_포지션_없는_전략은_합산에서_빠진다() // 종료 사이클 전략 holdings 100, 진행 전략 10, 브로커 10 → 알림 없음
@Test void 조회_실패_계좌는_건너뛰고_다른_계좌는_계속() // 계좌 A getPresentBalance throws, 계좌 B 괴리
    // → reportError 2회: A는 메시지 contains "[원장 대사] 브로커 잔고 조회 실패", B는 "실보유 부족"
@Test void 원장을_쓰지_않는다() // 모든 케이스 후 verify(cyclePositionPort, never()).save(any()); verify(strategyPort, never()).save(any())
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.application.service.LedgerReconciliationServiceTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패

- [ ] **Step 3: Implement `LedgerReconciliationService implements LedgerReconciliationUseCase`** (`@Service`, `@RequiredArgsConstructor`, `@Slf4j`, `@Transactional` 없음 — 외부 HTTP 호출이 있고 쓰기가 없다)
  - 흐름: 비코인 계좌 → 일괄 조회(전략 by accountIds → 최신 사이클 by strategyIds → `endDate()==null`만 → 포지션 by cycleIds, 포지션 없으면 제외) → 계좌마다 `brokerHoldings`(MOCK이면 `null`, 아니면 `getPresentBalance` items를 `Map<StrategyTicker,Integer>`로) → `check` → findings 비어있지 않으면 계좌당 1회 `reportError(new IllegalStateException("[원장 대사] " + nickname + " (accountId=" + id + ")\n" + findings 줄바꿈 join))`.
  - 계좌별 `try/catch (RuntimeException e)`: `log.warn` 후 `reportError(new IllegalStateException("[원장 대사] 브로커 잔고 조회 실패: " + nickname + " (accountId=" + id + ")", e))`.
  - 전략이 없는 계좌는 브로커 호출도 하지 않는다.

- [ ] **Step 4: Run service test** — Step 2 명령, Expected: PASS

- [ ] **Step 5: Write scheduler test, then implement `LedgerReconciliationScheduler`** (`ApiKeyExpirySchedulerTest` 패턴)
  - Test `run_은_락_이름과_TTL로_유스케이스를_실행한다`: `SchedulerLockService` mock이 Runnable을 즉시 실행하도록 stub → `verify(schedulerLockService).tryRun(eq("ledger-reconciliation"), eq(Duration.ofMinutes(30)), any())`, `verify(useCase).reconcileAll()`.
  - 구현: `@Component`, `@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true)`, `@Scheduled(cron = "0 0 9 * * TUE-SAT", zone = TimeZones.KST_ID) // 화~토 09:00 KST — 마감 리포트 후·개장 배치 전`, `run()`이 `schedulerLockService.tryRun("ledger-reconciliation", Duration.ofMinutes(30), useCase::reconcileAll)`. `tryRun`의 실제 시그니처(checked `InterruptedException` 등)는 `ApiKeyExpiryScheduler`를 따른다.

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.trading.adapter.in.schedule.LedgerReconciliationSchedulerTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → PASS

- [ ] **Step 6: 문서·아키텍처 맵**
  - `scheduler-time-table.md` 거래 관련 표에 행 추가: `| 전략 원장 대사 (`kista-trading`) | 화~토 09:00 KST | 30m | 실보유 < 원장 holdings 합·원장 음수 계좌만 관리자 알림 (매매 경로 미사용) |`
  - `modules/tradingcore/trading.md`에 한 줄: `LedgerReconciliationService`는 `PortfolioPort`로 실잔고를 읽지만 결과는 관리자 알림 전용 — 매매 판단·원장에 쓰지 않는다(실잔고 비사용 원칙).
  - `flows.yml` ApiKeyExpiry 흐름 옆에 항목: title `전략 원장 대사·알림`, desc `화~토 09:00 KST(락 ledger-reconciliation) 비코인 계좌의 종목별 실보유를 진행 중 사이클 원장 holdings 합과 대조하고 원장 음수를 검사해, 괴리 계좌만 관리자 알림. MOCK은 음수만.`, code `[LedgerReconciliationScheduler#run, LedgerReconciliationService#reconcileAll]`, cond `괴리가 있을 때만`. 기존 항목의 키 구조를 그대로 따른다.

- [ ] **Step 7: Verify architecture + map**

Run: `./gradlew :trading-core:test --tests 'com.kista.tradingcore.architecture.*' 2>&1 | grep -E "FAILED|BUILD|error:"` → PASS
Run: `./gradlew :test --tests '*ArchitectureMapTest' 2>&1 | grep -E "FAILED|BUILD|error:"` → PASS

- [ ] **Step 8: Commit**

```bash
git add trading-core/src/main/java/com/kista/tradingcore/trading/application/usecase/LedgerReconciliationUseCase.java \
  trading-core/src/main/java/com/kista/tradingcore/trading/application/service/LedgerReconciliationService.java \
  trading-core/src/main/java/com/kista/tradingcore/trading/adapter/in/schedule/LedgerReconciliationScheduler.java \
  trading-core/src/test/java/com/kista/tradingcore/trading/application/service/LedgerReconciliationServiceTest.java \
  trading-core/src/test/java/com/kista/tradingcore/trading/adapter/in/schedule/LedgerReconciliationSchedulerTest.java \
  docs/agents/scheduler-time-table.md docs/agents/modules/tradingcore/trading.md src/test/resources/architecture/flows.yml
git commit -m "feat(trading): 전략 원장 대사 일일 알림을 추가한다"
```

---

### 최종 검증 (컨트롤러, 1회)

- `./gradlew :trading-core:test 2>&1 | grep -E "FAILED|BUILD"` → `BUILD SUCCESSFUL`
- `./gradlew :test --tests 'com.kista.api.architecture.*' 2>&1 | grep -E "FAILED|BUILD"` → PASS
