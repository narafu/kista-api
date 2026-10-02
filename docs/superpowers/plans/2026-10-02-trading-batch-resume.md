# 매매 배치 재기동 재개 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `kista-trading`이 매매 배치 도중 graceful 재기동돼도 기동 시 체크포인트부터 재개해, 주문 중복·리포트 중복 없이 그날 매매를 끝내게 한다.

**Architecture:**
- 배치는 `TradingBatchRunState`에 자기 스레드와 임계구역(접수·리포트) 여부를 등록한다.
- `SmartLifecycle` 코디네이터가 종료 시 임계구역이 끝나길 기다린 뒤 대기 구간만 인터럽트한다.
- `TradingService`는 (job, 거래일) 단계(`PLANNED → PLACING → PLACED → DONE`)를 `trading.trading_batch_run`에 기록한다. `CyclePositionPersistor`는 전략별 리포트 마커를 `trading.trading_batch_report`에 남긴다.
- 기동 시 `TradingBatchResumer`가 단계와 시각으로 전체 재실행 / 리포트만 재개 / 알림을 고른다.

**Tech Stack:** Java 21, Spring Boot 4 (SmartLifecycle, ApplicationReadyEvent, JdbcTemplate), Flyway, JUnit 5 + Mockito + AssertJ, 로컬 PostgreSQL(`kistadb_test`).

**Spec:** `docs/superpowers/specs/2026-10-02-trading-batch-resume-design.md`

## Global Constraints

- 작업 위치는 worktree `C:\Users\USER\workspace\kista\kista-api-resume`, 브랜치 `feat/trading-batch-resume`. 모든 명령은 이 디렉토리에서 `bash gradlew ...`로 실행한다.
- 커밋 author는 `narafu <narafu@kakao.com>`. 메시지는 한글 Conventional Commit이고 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`를 붙인다. push 금지.
- 매매 공식(`docs/agents/modules/trading-formulas.md`) 변경 금지.
- 주석은 `//` 인라인만 쓴다(Javadoc·블록 주석 금지). 필드에는 역할 한 줄 주석을 단다.
- 아웃바운드 포트 이름은 `*Port`이고 위치는 `application/port/output`이다. `adapter/in`은 `application.service` 구현체에 의존할 수 없다(ArchUnit).
- Enum ↔ DB는 VARCHAR + `@Enumerated(STRING)`/문자열 매핑이다. PostgreSQL ENUM 금지.
- 테스트 실행은 서브프로젝트를 지정한다(`:trading-core:test --tests ...`). 전체 스위트는 Task 6에서 1회만 돌린다.
- DB 테스트 전 `docker compose up -d postgres`가 필요하다. `@Execution(ExecutionMode.SAME_THREAD)`도 필수다.
- `@Async`/`CompletableFuture` 금지, 대기는 `Thread.sleep`(Virtual Thread 환경).

## Review Focus

1. **자정 이후 개장 재개**: 화요일 00:30에 `trading-open`을 재개하면 거래일은 화요일이다. 개장 대기는 0이어야 한다(그날 밤 22:30까지 기다리면 안 된다). 테스트는 Task 5 `DstInfoForOpenBatchTest`.
2. **기동 직후 cron 이중 실행**: 같은 프로세스가 cron으로 이미 락을 쥐었으면 `takeOver`가 실패해야 한다. 테스트는 Task 2 `takeOver_ownLockHeld_skips`.
3. **청산 rotation 후 리포트 재개**: 리포트가 사이클을 rotate한 전략은 재개 때 새 사이클 ID로 보이지만 마커(전략 키)로 건너뛰어야 한다. 테스트는 Task 4 `resumeCloseReport_skipsReportedStrategy`.
4. **종료 인터럽트 시 사용자 "미접수" 알림 오발송**: `stopping` 상태의 인터럽트에서는 `BatchInterruptedEvent`가 나가면 안 된다. 테스트는 Task 4 `executeBatch_stoppingDuringOrderWait_suppressesUserNotice`.
5. **접수 마감 경계**: DST 기준 04:50(장마감 05:00 − 10분) 이후의 미접수 재개는 실행하지 않고 알림만 보내야 한다. 테스트는 Task 5 `close_planned_afterCutoff_alertsOnly`.

---

### Task 1: 체크포인트 테이블·포트·어댑터

**Files:**
- Create: `trading-core/src/main/resources/db/migration-trading/V4__trading_batch_run.sql`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/TradingBatchJob.java`
- Create: `trading-core/src/main/java/com/kista/trading/domain/model/TradingBatchPhase.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/port/output/TradingBatchRunPort.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapter.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapterDbTest.java`

**Interfaces:**
- Produces:
  - `enum TradingBatchJob { CLOSE, OPEN; String lockName() }`. 락 이름은 `"trading-close"`, `"trading-open"`.
  - `enum TradingBatchPhase { PLANNED, PLACING, PLACED, DONE }`
  - `TradingBatchRunPort`:
    - `Optional<TradingBatchPhase> findPhase(TradingBatchJob job, LocalDate tradeDate)`
    - `void recordPhase(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase)`
    - `boolean isReported(LocalDate tradeDate, UUID strategyId)`
    - `void markReported(LocalDate tradeDate, UUID strategyId)`

- [ ] **Step 1: 마이그레이션 작성**

`V4__trading_batch_run.sql`:
```sql
-- 매매 배치 재개 체크포인트 — kista-trading 재기동 시 TradingBatchResumer가 (job, KST 거래일) 단계로 재개 지점을 판정한다
CREATE TABLE trading.trading_batch_run (
    job_name   VARCHAR(50) NOT NULL,
    trade_date DATE        NOT NULL,
    phase      VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_trading_batch_run PRIMARY KEY (job_name, trade_date)
);

-- 전략별 당일 리포트 완료 마커 — 리포트 재개 시 cycle_position 중복 append·리포트 재발송 방지
-- 사이클이 아닌 전략 키: 청산 rotation이 새 사이클을 만들어도 재개 판정이 흔들리지 않도록
CREATE TABLE trading.trading_batch_report (
    trade_date  DATE        NOT NULL,
    strategy_id UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_trading_batch_report PRIMARY KEY (trade_date, strategy_id)
);
```

- [ ] **Step 2: 도메인 enum·포트 작성**

`TradingBatchJob.java`:
```java
package com.kista.trading.domain.model;

// 재개 대상 매매 배치 — lockName은 scheduler_locks 이름·trading_batch_run.job_name과 동일 값
public enum TradingBatchJob {
    CLOSE("trading-close"), // 마감 매매 배치 (TradingCloseScheduler)
    OPEN("trading-open");   // 개장 선접수 배치 (TradingOpenScheduler)

    private final String lockName; // 스케쥴러 락·체크포인트 키

    TradingBatchJob(String lockName) {
        this.lockName = lockName;
    }

    public String lockName() {
        return lockName;
    }
}
```

`TradingBatchPhase.java`:
```java
package com.kista.trading.domain.model;

// 매매 배치 진행 단계 — 재기동 재개 판정 기준 (마감: PLANNED→PLACING→PLACED→DONE, 개장: PLACING→DONE)
public enum TradingBatchPhase {
    PLANNED, // PLANNED 주문 저장 완료 — 접수 대기 중
    PLACING, // 증권사 접수 진입 — 이 단계에서 중단되면 이중 접수 여부 확인 필요
    PLACED,  // 접수 완료 — 마감 후 리포트 대기 중
    DONE     // 배치 종료(조기 반환 포함)
}
```

`TradingBatchRunPort.java`:
```java
package com.kista.trading.application.port.output;

import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

// 매매 배치 재개 체크포인트 저장소 — 단계 기록 + 전략별 리포트 완료 마커
public interface TradingBatchRunPort {
    // (job, 거래일) 마지막 기록 단계 — 실행 이력 없으면 empty
    Optional<TradingBatchPhase> findPhase(TradingBatchJob job, LocalDate tradeDate);

    // 단계 upsert
    void recordPhase(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase);

    // 전략의 당일 리포트(cycle_position 저장) 완료 여부
    boolean isReported(LocalDate tradeDate, UUID strategyId);

    // 리포트 완료 마커 기록 — 중복 호출 무해
    void markReported(LocalDate tradeDate, UUID strategyId);
}
```

- [ ] **Step 3: 실패하는 DB 테스트 작성**

`TradingBatchRunPersistenceAdapterDbTest.java`:
```java
package com.kista.trading.adapter.out.persistence;

import com.kista.support.DataJpaTestBase;
import com.kista.support.TradingCoreJpaTestConfig;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 체크포인트 upsert·마커 멱등 insert 실 DB 왕복 검증
@Import(TradingBatchRunPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)
class TradingBatchRunPersistenceAdapterDbTest extends DataJpaTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Autowired TradingBatchRunPersistenceAdapter adapter;

    @Test
    void findPhase_noRow_empty() {
        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY)).isEmpty();
    }

    @Test
    void recordPhase_upsertsLatestPhasePerJob() {
        adapter.recordPhase(TradingBatchJob.CLOSE, DAY, TradingBatchPhase.PLANNED);
        adapter.recordPhase(TradingBatchJob.CLOSE, DAY, TradingBatchPhase.PLACED);
        adapter.recordPhase(TradingBatchJob.OPEN, DAY, TradingBatchPhase.DONE);

        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY)).contains(TradingBatchPhase.PLACED);
        assertThat(adapter.findPhase(TradingBatchJob.OPEN, DAY)).contains(TradingBatchPhase.DONE);
        assertThat(adapter.findPhase(TradingBatchJob.CLOSE, DAY.plusDays(1))).isEmpty();
    }

    @Test
    void markReported_idempotent_andScopedByDateAndStrategy() {
        UUID strategyId = UUID.randomUUID();

        adapter.markReported(DAY, strategyId);
        adapter.markReported(DAY, strategyId); // 중복 호출 무해

        assertThat(adapter.isReported(DAY, strategyId)).isTrue();
        assertThat(adapter.isReported(DAY.plusDays(1), strategyId)).isFalse();
        assertThat(adapter.isReported(DAY, UUID.randomUUID())).isFalse();
    }
}
```

`TradingCoreJpaTestConfig`의 실제 패키지는 `trading-core/src/test`에서 `grep -rn "class TradingCoreJpaTestConfig"`로 확인하고 import를 맞춘다.

- [ ] **Step 4: 실패 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.adapter.out.persistence.TradingBatchRunPersistenceAdapterDbTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`TradingBatchRunPersistenceAdapter` 없음).

- [ ] **Step 5: 어댑터 구현**

`TradingBatchRunPersistenceAdapter.java`:
```java
package com.kista.trading.adapter.out.persistence;

import com.kista.trading.application.port.output.TradingBatchRunPort;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

// 매매 배치 체크포인트 — 단순 upsert/exists라 JPA 엔티티 대신 JdbcTemplate (search_path=trading)
@Component
@RequiredArgsConstructor
class TradingBatchRunPersistenceAdapter implements TradingBatchRunPort {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<TradingBatchPhase> findPhase(TradingBatchJob job, LocalDate tradeDate) {
        return jdbcTemplate.queryForList(
                        "SELECT phase FROM trading_batch_run WHERE job_name = ? AND trade_date = ?",
                        String.class, job.lockName(), tradeDate)
                .stream().findFirst().map(TradingBatchPhase::valueOf);
    }

    @Override
    public void recordPhase(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase) {
        jdbcTemplate.update("""
                INSERT INTO trading_batch_run (job_name, trade_date, phase, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (job_name, trade_date) DO UPDATE
                   SET phase = EXCLUDED.phase,
                       updated_at = now()
                """, job.lockName(), tradeDate, phase.name());
    }

    @Override
    public boolean isReported(LocalDate tradeDate, UUID strategyId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM trading_batch_report WHERE trade_date = ? AND strategy_id = ?)",
                Boolean.class, tradeDate, strategyId));
    }

    @Override
    public void markReported(LocalDate tradeDate, UUID strategyId) {
        jdbcTemplate.update("""
                INSERT INTO trading_batch_report (trade_date, strategy_id)
                VALUES (?, ?)
                ON CONFLICT (trade_date, strategy_id) DO NOTHING
                """, tradeDate, strategyId);
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.adapter.out.persistence.TradingBatchRunPersistenceAdapterDbTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋**

```bash
git add trading-core/src/main/resources/db/migration-trading/V4__trading_batch_run.sql \
  trading-core/src/main/java/com/kista/trading/domain/model/TradingBatchJob.java \
  trading-core/src/main/java/com/kista/trading/domain/model/TradingBatchPhase.java \
  trading-core/src/main/java/com/kista/trading/application/port/output/TradingBatchRunPort.java \
  trading-core/src/main/java/com/kista/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapter.java \
  trading-core/src/test/java/com/kista/trading/adapter/out/persistence/TradingBatchRunPersistenceAdapterDbTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 매매 배치 재개 체크포인트 테이블·포트 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `SchedulerLockService.takeOver` + owner 고유화

**Files:**
- Modify: `shared/src/main/java/com/kista/platform/scheduling/SchedulerLockService.java`
- Test: `trading-core/src/test/java/com/kista/platform/scheduling/SchedulerLockServiceDbTest.java`. shared에는 DB 테스트 기반이 없어서, `scheduler_locks`가 있는 trading-core 테스트 DB를 쓴다.

**Interfaces:**
- Produces: `public boolean takeOver(String lockName, Duration lockAtMostFor, LockedTask task) throws InterruptedException`. 인수에 실패하면(자기 프로세스가 보유 중) `false`를 반환하고 task를 실행하지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package com.kista.platform.scheduling;

import com.kista.support.DataJpaTestBase;
import com.kista.support.TradingCoreJpaTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

// takeOver: 다른 owner의 미만료 락은 인수, 자기 owner 락은 인수 거부 (기동 직후 cron과의 이중 실행 방지)
@Import(SchedulerLockService.class)
@Execution(ExecutionMode.SAME_THREAD)
@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)
class SchedulerLockServiceDbTest extends DataJpaTestBase {

    private static final String LOCK = "test-takeover-lock";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SchedulerLockService lockService;

    @Test
    void takeOver_foreignUnexpiredLock_runsAndMarksFinished() throws InterruptedException {
        // 죽은 이전 프로세스가 남긴 미만료·미완료 락
        jdbcTemplate.update("""
                INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by, finished_at)
                VALUES (?, now() + interval '2 hours', now(), 'dead-host-1@dead', NULL)
                """, LOCK);
        AtomicBoolean ran = new AtomicBoolean();

        boolean result = lockService.takeOver(LOCK, Duration.ofHours(3), () -> ran.set(true));

        assertThat(result).isTrue();
        assertThat(ran).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_at IS NOT NULL FROM scheduler_locks WHERE name = ?", Boolean.class, LOCK)).isTrue();
    }

    @Test
    void takeOver_ownLockHeld_skips() throws InterruptedException {
        AtomicBoolean innerRan = new AtomicBoolean();
        // 같은 프로세스가 cron(tryRun)으로 이미 실행 중인 상황
        lockService.tryRun(LOCK, Duration.ofHours(3), () -> {
            try {
                boolean result = lockService.takeOver(LOCK, Duration.ofHours(3), () -> innerRan.set(true));
                assertThat(result).isFalse();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(innerRan).isFalse();
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.platform.scheduling.SchedulerLockServiceDbTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`takeOver` 없음).

- [ ] **Step 3: 구현**

`SchedulerLockService.java`를 다음처럼 바꾼다.
- `tryRun` 본문의 실행부를 `runAcquired`로 추출한다.
- `takeOver`·`forceAcquire`를 추가한다.
- `buildOwnerId`에 UUID 접미사를 붙인다.

```java
    public boolean tryRun(String lockName, Duration lockAtMostFor, LockedTask task) throws InterruptedException {
        if (!tryAcquire(lockName, lockAtMostFor)) {
            log.info("[{}] 스케쥴러 락 획득 실패 — 다른 인스턴스가 실행 중", lockName);
            return false;
        }
        return runAcquired(lockName, task);
    }

    // 재기동 재개 전용 — 죽은 이전 프로세스가 남긴 미만료 락을 인수해 실행한다.
    // 단일 인스턴스·비겹침 배포 전제(spec 3장)에서만 안전. 자기 프로세스가 쥔 락(기동 직후 cron 선발화)은 인수하지 않는다
    public boolean takeOver(String lockName, Duration lockAtMostFor, LockedTask task) throws InterruptedException {
        if (!forceAcquire(lockName, lockAtMostFor)) {
            log.info("[{}] 락 인수 생략 — 이 프로세스가 이미 실행 중", lockName);
            return false;
        }
        log.warn("[{}] 이전 프로세스 락 인수 — 재개 실행", lockName);
        return runAcquired(lockName, task);
    }

    // 작업 성공 시 락을 lockAtMostFor 동안 유지 — 즉시 해제하면 스케쥴링 지터로 다른 인스턴스가 순차 획득 가능
    // 작업 실패(예외) 시에만 즉시 해제해 다른 인스턴스가 재시도할 수 있도록 허용
    private boolean runAcquired(String lockName, LockedTask task) throws InterruptedException {
        boolean completed = false;
        try {
            task.run();
            completed = true;
            markFinished(lockName);
            return true;
        } finally {
            if (!completed) {
                release(lockName);
            }
        }
    }

    // tryAcquire와 같되 만료 전이라도 다른 owner 락이면 덮어쓴다
    private boolean forceAcquire(String lockName, Duration lockAtMostFor) {
        List<String> rows = jdbcTemplate.queryForList("""
                INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by, finished_at)
                VALUES (?, now() + (? * interval '1 millisecond'), now(), ?, NULL)
                ON CONFLICT (name) DO UPDATE
                   SET lock_until = EXCLUDED.lock_until,
                       locked_at = EXCLUDED.locked_at,
                       locked_by = EXCLUDED.locked_by,
                       finished_at = NULL
                 WHERE scheduler_locks.lock_until <= now()
                    OR scheduler_locks.locked_by <> EXCLUDED.locked_by
                RETURNING name
                """, String.class, lockName, lockAtMostFor.toMillis(), ownerId);
        return !rows.isEmpty();
    }
```

`buildOwnerId`를 다음처럼 바꾼다. 컨테이너 restart처럼 hostname·pid가 같아도 이전 프로세스와 구분돼야 한다.
```java
    // 프로세스 실행마다 고유 — docker restart처럼 hostname·pid가 같은 재기동도 이전 프로세스와 구분돼야 takeOver가 성립
    private static String buildOwnerId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ManagementFactory.getRuntimeMXBean().getName() + "-" + suffix;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
```

`locked_by` 컬럼 길이가 늘어난 값을 담을 수 있는지 확인한다: `grep -n "locked_by" trading-core/src/main/resources/db/migration-trading/V1__init.sql src/main/resources/db/migration/V1__init.sql`. VARCHAR(255) 이상이면 그대로 두고, 더 작으면 이 Task를 멈추고 보고한다.

- [ ] **Step 4: 통과 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.platform.scheduling.SchedulerLockServiceDbTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add shared/src/main/java/com/kista/platform/scheduling/SchedulerLockService.java \
  trading-core/src/test/java/com/kista/platform/scheduling/SchedulerLockServiceDbTest.java
git commit -m "$(cat <<'EOF'
feat(platform): 재기동 재개용 스케쥴러 락 인수(takeOver) 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 배치 실행 상태 + 협조적 종료 코디네이터

**Files:**
- Create: `trading-core/src/main/java/com/kista/trading/application/service/support/TradingBatchRunState.java`
- Create: `trading-core/src/main/java/com/kista/trading/application/service/support/TradingBatchShutdownCoordinator.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/support/TradingBatchRunStateTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/support/TradingBatchShutdownCoordinatorTest.java`

**Interfaces:**
- Produces:
  - `TradingBatchRunState`:
    - `void track(BatchTask task) throws InterruptedException`. `BatchTask { void run() throws InterruptedException; }`
    - `<T> T critical(CriticalTask<T> task) throws InterruptedException`. `CriticalTask<T> { T call() throws InterruptedException; }`
    - `void sleep(Duration duration) throws InterruptedException`
    - `boolean isStopping()`
    - `boolean requestStop(Duration maxCriticalWait)`. 임계구역 상한을 넘기면 `false`.
  - `TradingBatchShutdownCoordinator(TradingBatchRunState, ApplicationEventPublisher, @Value("${trading.shutdown.critical-wait:150s}") Duration)`

- [ ] **Step 1: 실패하는 테스트 작성**

`TradingBatchRunStateTest.java`:
```java
package com.kista.trading.application.service.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingBatchRunStateTest {

    @Test
    void requestStop_whileSleeping_interruptsImmediately() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch sleeping = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> {
                    sleeping.countDown();
                    state.sleep(Duration.ofHours(1));
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        sleeping.await(5, TimeUnit.SECONDS);
        Thread.sleep(50); // sleep 진입 대기

        boolean stoppedCleanly = state.requestStop(Duration.ofSeconds(5));

        batch.join(5_000);
        assertThat(stoppedCleanly).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(state.isStopping()).isTrue();
    }

    @Test
    void requestStop_whileCritical_waitsUntilCriticalEnds_thenInterruptsNextSleep() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch inCritical = new CountDownLatch(1);
        CountDownLatch releaseCritical = new CountDownLatch(1);
        AtomicBoolean criticalCompleted = new AtomicBoolean();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> {
                    state.critical(() -> {
                        inCritical.countDown();
                        releaseCritical.await();
                        criticalCompleted.set(true);
                        return null;
                    });
                    state.sleep(Duration.ofHours(1)); // 임계구역 뒤 대기 — 종료 요청으로 즉시 중단돼야 함
                });
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        inCritical.await(5, TimeUnit.SECONDS);

        Thread stopper = Thread.ofVirtual().start(() -> state.requestStop(Duration.ofSeconds(10)));
        Thread.sleep(100);
        assertThat(criticalCompleted).isFalse(); // 임계구역은 인터럽트되지 않고 진행 중
        releaseCritical.countDown();

        stopper.join(5_000);
        batch.join(5_000);
        assertThat(criticalCompleted).isTrue();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void requestStop_criticalExceedsLimit_returnsFalse() throws Exception {
        TradingBatchRunState state = new TradingBatchRunState();
        CountDownLatch inCritical = new CountDownLatch(1);
        CountDownLatch releaseCritical = new CountDownLatch(1);
        Thread batch = Thread.ofVirtual().start(() -> {
            try {
                state.track(() -> state.critical(() -> {
                    inCritical.countDown();
                    releaseCritical.await();
                    return null;
                }));
            } catch (InterruptedException ignored) {
            }
        });
        inCritical.await(5, TimeUnit.SECONDS);

        assertThat(state.requestStop(Duration.ofMillis(100))).isFalse();

        releaseCritical.countDown();
        batch.join(5_000);
    }

    @Test
    void sleep_afterStopRequestedWithoutBatch_throwsImmediately() {
        TradingBatchRunState state = new TradingBatchRunState();
        assertThat(state.requestStop(Duration.ZERO)).isTrue(); // 실행 중 배치 없음

        assertThatThrownBy(() -> state.sleep(Duration.ofHours(1))).isInstanceOf(InterruptedException.class);
    }
}
```

`TradingBatchShutdownCoordinatorTest.java`:
```java
package com.kista.trading.application.service.support;

import com.kista.trading.application.event.TradingErrorEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TradingBatchShutdownCoordinatorTest {

    @Test
    void stop_criticalTimeout_publishesAdminAlert() {
        TradingBatchRunState state = mock(TradingBatchRunState.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(state.requestStop(Duration.ofSeconds(150))).thenReturn(false);
        TradingBatchShutdownCoordinator coordinator = new TradingBatchShutdownCoordinator(state, events, Duration.ofSeconds(150));
        coordinator.start();

        coordinator.stop();

        verify(events).publishEvent(argThat((Object e) -> e instanceof TradingErrorEvent t
                && t.userId() == null && t.message().contains("수동 확인 필요")));
    }

    @Test
    void stop_clean_noAlert() {
        TradingBatchRunState state = mock(TradingBatchRunState.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(state.requestStop(any())).thenReturn(true);
        TradingBatchShutdownCoordinator coordinator = new TradingBatchShutdownCoordinator(state, events, Duration.ofSeconds(150));
        coordinator.start();

        coordinator.stop();

        verifyNoInteractions(events);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.application.service.support.TradingBatch*Test' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패.

- [ ] **Step 3: 구현**

`TradingBatchRunState.java`:
```java
package com.kista.trading.application.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

// 실행 중 매매 배치 스레드와 임계구역(증권사 접수·리포트) 여부 — 종료 시 대기 구간만 끊고 임계구역은 완료를 기다리기 위한 상태
@Slf4j
@Component
public class TradingBatchRunState {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition criticalExited = lock.newCondition(); // 임계구역 이탈 신호
    private Thread batchThread;        // 추적 중인 배치 스레드 (없으면 null)
    private boolean critical;          // 임계구역 진행 여부
    private volatile boolean stopping; // 종료 요청 수신 — 이후 대기는 즉시 중단

    // 배치 전체를 추적 대상으로 실행
    // ponytail: 단일 슬롯 — 개장(22:30~)·마감(04:30~) 배치는 시간상 겹치지 않음. 겹치면 뒤 배치는 추적 없이 실행(종료 시 보호 안 됨)
    public void track(BatchTask task) throws InterruptedException {
        boolean owner = register();
        try {
            task.run();
        } finally {
            if (owner) unregister();
        }
    }

    // 임계구역 실행 — 종료 요청이 와도 이 구간은 인터럽트하지 않고 완료를 기다린다
    public <T> T critical(CriticalTask<T> task) throws InterruptedException {
        boolean owner = setCritical(true);
        try {
            return task.call();
        } finally {
            if (owner) setCritical(false);
        }
    }

    // 배치 대기 — 종료 요청 상태면 대기 없이 즉시 InterruptedException
    public void sleep(Duration duration) throws InterruptedException {
        if (stopping) throw new InterruptedException("종료 요청 — 대기 중단");
        long ms = duration.toMillis();
        if (ms > 0) Thread.sleep(ms);
    }

    public boolean isStopping() {
        return stopping;
    }

    // 종료 요청 — 임계구역이면 이탈까지 최대 maxCriticalWait 대기 후 배치 스레드 인터럽트. 상한 초과 시 false
    public boolean requestStop(Duration maxCriticalWait) {
        lock.lock();
        try {
            stopping = true;
            if (batchThread == null) return true;
            long nanos = maxCriticalWait.toNanos();
            while (critical && nanos > 0) {
                nanos = criticalExited.awaitNanos(nanos);
            }
            if (critical) return false;
            batchThread.interrupt();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !critical;
        } finally {
            lock.unlock();
        }
    }

    private boolean register() {
        lock.lock();
        try {
            if (batchThread != null) {
                log.warn("매매 배치 추적 슬롯 사용 중 — 이 배치는 종료 보호 없이 실행");
                return false;
            }
            batchThread = Thread.currentThread();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void unregister() {
        lock.lock();
        try {
            batchThread = null;
            critical = false;
            criticalExited.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // 추적 중인 배치 스레드에서만 임계구역 상태 변경 — 다른 스레드 호출은 무시(false)
    private boolean setCritical(boolean value) {
        lock.lock();
        try {
            if (batchThread != Thread.currentThread()) return false;
            critical = value;
            if (!value) criticalExited.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface BatchTask {
        void run() throws InterruptedException;
    }

    @FunctionalInterface
    public interface CriticalTask<T> {
        T call() throws InterruptedException;
    }
}
```

`TimeUnit` import는 쓰지 않으므로 넣지 않는다.

`TradingBatchShutdownCoordinator.java`:
```java
package com.kista.trading.application.service.support;

import com.kista.trading.application.event.TradingErrorEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 협조적 종료 — 웹서버 graceful보다 먼저 멈춰, 배치 대기 구간은 즉시 끊고 접수·리포트 임계구역은 완료를 기다린다
// stop()은 동기 블로킹이라 timeout-per-shutdown-phase 영향 없음 — 상한(150s) + 웹 graceful(30s)이 compose stop_grace_period(200s) 안
@Slf4j
@Component
public class TradingBatchShutdownCoordinator implements SmartLifecycle {

    private final TradingBatchRunState runState;
    private final ApplicationEventPublisher eventPublisher; // 상한 초과 관리자 알림
    private final Duration criticalWait;                   // 임계구역 완료 대기 상한
    private volatile boolean running;                      // SmartLifecycle 실행 상태

    public TradingBatchShutdownCoordinator(TradingBatchRunState runState,
                                           ApplicationEventPublisher eventPublisher,
                                           @Value("${trading.shutdown.critical-wait:150s}") Duration criticalWait) {
        this.runState = runState;
        this.eventPublisher = eventPublisher;
        this.criticalWait = criticalWait;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        if (!runState.requestStop(criticalWait)) {
            log.error("매매 배치 임계구역이 {} 안에 끝나지 않음 — 강제 종료", criticalWait);
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[재기동] 접수/리포트 진행 중 강제 종료 — 수동 확인 필요"));
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 웹서버 graceful(SmartLifecycle.DEFAULT_PHASE - 1024)보다 먼저 stop
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.application.service.support.TradingBatch*Test' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/application/service/support/TradingBatchRunState.java \
  trading-core/src/main/java/com/kista/trading/application/service/support/TradingBatchShutdownCoordinator.java \
  trading-core/src/test/java/com/kista/trading/application/service/support/TradingBatchRunStateTest.java \
  trading-core/src/test/java/com/kista/trading/application/service/support/TradingBatchShutdownCoordinatorTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 매매 배치 협조적 종료 코디네이터 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `TradingService` 체크포인트·임계구역·리포트 재개 + 리포트 마커

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingService.java`. 필드, `executeBatch`, `placeOpenOrders`, `waitFor`를 바꾸고 `resumeCloseReport`를 추가한다.
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java`. 필드와 `saveCyclePosition`을 바꾼다.
- Modify: `trading-core/src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java`
- Modify: `trading-core/src/main/java/com/kista/trading/application/service/TradingExecutionFacade.java`
- Modify: `trading-core/src/main/java/com/kista/trading/domain/model/DstInfo.java`. `forOpenBatch`, `marketCloseAt`을 추가하고 `calculate(ZonedDateTime)`을 public으로 바꾼다.
- Test: `trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/application/service/CyclePositionPersistorTest.java`
- Test: `trading-core/src/test/java/com/kista/trading/domain/model/DstInfoForOpenBatchTest.java`

**Interfaces:**
- Consumes:
  - Task 1: `TradingBatchRunPort`, `TradingBatchJob`, `TradingBatchPhase`.
  - Task 3: `TradingBatchRunState`.
- Produces:
  - `TradingExecutionUseCase.resumeCloseReport(List<BatchContext> contexts) throws InterruptedException`
  - `TradingService.resumeCloseReport(List<BatchContext>, DstInfo)`, package-private.
  - `DstInfo.forOpenBatch(LocalDate tradeDate)`, `DstInfo.marketCloseAt()`, `public static DstInfo calculate(ZonedDateTime)`.

- [ ] **Step 1: DstInfo 테스트 작성 (실패)**

`DstInfoForOpenBatchTest.java`:
```java
package com.kista.trading.domain.model;

import com.kista.sharedkernel.TimeZones;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DstInfoForOpenBatchTest {

    @Test
    void forOpenBatch_marketOpenIsEveningBeforeTradeDate() {
        // 2026-10-07(수) 거래일 → 개장은 10-06(화) 22:30 KST (10월은 미국 DST)
        DstInfo dst = DstInfo.forOpenBatch(LocalDate.of(2026, 10, 7));

        assertThat(dst.marketOpen()).isEqualTo(ZonedDateTime.of(2026, 10, 6, 22, 30, 0, 0, TimeZones.KST).toInstant());
    }

    @Test
    void marketCloseAt_dst_is0500SameKstDate() {
        DstInfo dst = DstInfo.calculate(ZonedDateTime.of(2026, 10, 7, 4, 40, 0, 0, TimeZones.KST));

        assertThat(dst.marketCloseAt()).isEqualTo(ZonedDateTime.of(2026, 10, 7, 5, 0, 0, 0, TimeZones.KST).toInstant());
    }

    @Test
    void marketCloseAt_nonDst_is0600() {
        DstInfo dst = DstInfo.calculate(ZonedDateTime.of(2026, 12, 2, 5, 0, 0, 0, TimeZones.KST));

        assertThat(dst.marketCloseAt()).isEqualTo(ZonedDateTime.of(2026, 12, 2, 6, 0, 0, 0, TimeZones.KST).toInstant());
    }
}
```

- [ ] **Step 2: DstInfo 구현**

`DstInfo.java`:
- `static DstInfo calculate(ZonedDateTime nowKst)`를 `public static`으로 바꾼다.
- `immediateOpen()` 위에 다음을 추가한다.

```java
    // 개장 배치용: 거래일 T의 개장 시각(T-1일 저녁) — 자정 이후 재개돼도 이미 지난 개장을 기다리지 않도록 거래일 기준 산출
    public static DstInfo forOpenBatch(LocalDate tradeDate) {
        DstInfo now = calculate();
        return new DstInfo(now.isDst(), now.orderAt(), now.postClose(),
                atKst(tradeDate.minusDays(1), marketOpenTime(now.isDst())));
    }

    // orderAt과 같은 KST 일자의 장마감 시각 — 재개 시 마감 접수 마감 판정용
    public Instant marketCloseAt() {
        return atKst(orderAt.atZone(KST).toLocalDate(), marketCloseTime(isDst));
    }
```

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.domain.model.DstInfo*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: CyclePositionPersistor 마커 테스트 작성 (실패)**

`CyclePositionPersistorTest.java`:
- 생성자 호출(`new CyclePositionPersistor(`)에 마지막 인자로 `batchRunPort`(`@Mock TradingBatchRunPort batchRunPort;`)를 추가한다.
- 아래 테스트를 추가한다. 기존 테스트 중 INFINITE가 아닌 정상 저장 케이스 하나의 given 구성을 그대로 복사해 쓰고, `today`·`ctx` 변수명은 그 테스트를 따른다.

```java
    @Test
    void saveCyclePosition_marksStrategyReportedAfterPositionSaved() {
        // given — 기존 정상 저장 케이스와 동일 구성 (ctx, balance, price, today)
        // when
        persistor.saveCyclePosition(today, balance, ctx, price, null);
        // then — 포지션 저장 후 전략 키로 마커
        InOrder inOrder = inOrder(cyclePositionPort, batchRunPort);
        inOrder.verify(cyclePositionPort).save(any());
        inOrder.verify(batchRunPort).markReported(today, ctx.strategy().id());
    }
```

`TradingServiceTest.java` setUp의 `new CyclePositionPersistor(...)`에도 `batchRunPort` 인자를 추가한다(Step 6에서 같이 한다).

- [ ] **Step 4: CyclePositionPersistor 구현**

필드를 추가한다(마지막 필드).
```java
    private final TradingBatchRunPort batchRunPort;                             // 전략별 당일 리포트 완료 마커 (재개 시 중복 리포트 방지)
```
`saveCyclePosition`의 `log.info("[strategyId={}] 사이클 포지션 저장 완료 ...")` 바로 위에 다음을 넣는다.
```java
        // 리포트 완료 마커 — rotation이 새 사이클을 만들어도 재개 판정이 유지되도록 전략 키 (저장 직후·rotation 전)
        batchRunPort.markReported(today, strategy.id());
```

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.application.service.CyclePositionPersistorTest' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: TradingServiceTest 신규 테스트 작성 (실패)**

`TradingServiceTest.java` 수정:
- `@Mock TradingBatchRunPort batchRunPort;` 필드를 추가한다.
- `TradingBatchRunState runState;` 필드를 두고, setUp에서 `runState = new TradingBatchRunState();`로 만든다.
- `new TradingService(...)` 인자 끝에 `batchRunPort, runState, balanceLoader`를 추가한다. `balanceLoader`는 setUp에 이미 있는 `TradingBalanceLoader` 지역변수다.
- `new CyclePositionPersistor(...)` 끝에 `batchRunPort`를 추가한다.

추가 테스트는 아래와 같다. 기존 `execute_normalFlow_allPortsCalledInOrder`의 given(stub) 구성을 private helper로 추출하거나 복사해 재사용한다. 단언만 새로 쓴다.

```java
    @Test
    void executeBatch_normalFlow_recordsPhasesInOrder() throws InterruptedException {
        // given — execute_normalFlow_allPortsCalledInOrder와 동일 stub 구성
        // when
        service.execute(STRATEGY, ACCOUNT, USER, DstInfoTestHelper.pastAll()); // 기존 테스트가 쓰는 DstInfo 값을 그대로 사용
        // then
        LocalDate today = LocalDate.now(TimeZones.KST);
        InOrder inOrder = inOrder(batchRunPort);
        inOrder.verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLANNED);
        inOrder.verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLACING);
        inOrder.verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLACED);
        inOrder.verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
    }

    @Test
    void executeBatch_holiday_recordsDone() throws InterruptedException {
        when(marketCalendarPort.isMarketOpen(any())).thenReturn(false);

        service.executeBatch(List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), DstInfo.immediateClose());

        verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, LocalDate.now(TimeZones.KST), TradingBatchPhase.DONE);
        verify(batchRunPort, never()).recordPhase(any(), any(), eq(TradingBatchPhase.PLANNED));
    }

    @Test
    void executeBatch_stoppingDuringOrderWait_suppressesUserNotice() throws InterruptedException {
        // given — 정상 흐름 stub(계획까지 성공) + 주문 시각이 1시간 뒤인 DstInfo
        runState.requestStop(Duration.ZERO); // 종료 요청 상태 — 대기 진입 즉시 중단
        DstInfo futureOrder = new DstInfo(true, Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200), Instant.now());

        assertThatThrownBy(() -> service.executeBatch(
                List.of(new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER)), futureOrder))
                .isInstanceOf(InterruptedException.class);

        verify(eventPublisher, never()).publishEvent(any(BatchInterruptedEvent.class));
        verify(eventPublisher).publishEvent(argThat((Object e) -> e instanceof TradingErrorEvent t
                && t.message().startsWith("[재기동]")));
        verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, LocalDate.now(TimeZones.KST), TradingBatchPhase.PLANNED);
        verify(batchRunPort, never()).recordPhase(any(), any(), eq(TradingBatchPhase.PLACING));
    }

    @Test
    void resumeCloseReport_skipsReportedStrategy_reportsOthers_recordsDone() throws InterruptedException {
        // given — 전략 2개: STRATEGY는 이미 리포트됨, 다른 전략은 미리포트
        LocalDate today = LocalDate.now(TimeZones.KST);
        // 두 번째 context: 기존 픽스처 생성 방식(STRATEGY/STRATEGY_CYCLE 정의부)을 따라 다른 id로 생성
        BatchContext reported = new BatchContext(STRATEGY, STRATEGY_CYCLE, ACCOUNT, USER);
        BatchContext pending = /* STRATEGY와 다른 id의 전략·사이클로 만든 BatchContext */;
        when(batchRunPort.isReported(today, STRATEGY.id())).thenReturn(true);
        when(batchRunPort.isReported(today, pending.strategy().id())).thenReturn(false);
        // pending 전략의 최신 cycle_position(잔고), 확정 종가, 체결 조회 stub — 정상 흐름 테스트의 report 단계 stub과 동일

        service.resumeCloseReport(List.of(reported, pending), DstInfo.immediateClose());

        verify(kisExecutionPort, never()).getExecutions(any(), any(), any(), eq(reported.account().brokerRef()));
        verify(cycleHistoryPort).save(argThat(p -> p.strategyCycleId().equals(pending.currentCycle().id())));
        verify(cycleHistoryPort, never()).save(argThat(p -> p.strategyCycleId().equals(STRATEGY_CYCLE.id())));
        verify(batchRunPort).recordPhase(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
    }
```

`DstInfoTestHelper.pastAll()`은 예시 이름이다. 실제로는 `execute_normalFlow_allPortsCalledInOrder`가 넘기는 DstInfo 표현식을 그대로 쓴다. `pending` 생성식도 파일 상단 픽스처 정의를 보고 같은 방식으로 작성한다(빈칸으로 남기지 말 것). 두 전략이 같은 계좌·ticker여도 무방하다. `getExecutions` 검증은 계좌가 같으면 `times(1)`로 바꾼다.

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.application.service.TradingServiceTest' 2>&1 | grep -E "FAILED|BUILD|error:"`
Expected: 컴파일 실패(`resumeCloseReport`·생성자 인자 없음).

- [ ] **Step 6: TradingService 구현**

필드를 추가한다(`candidatePlanner` 아래).
```java
    private final TradingBatchRunPort batchRunPort;            // 재개 체크포인트 (단계 기록·리포트 마커 조회)
    private final TradingBatchRunState runState;               // 협조적 종료 — 배치 스레드·임계구역 등록
    private final TradingBalanceLoader balanceLoader;          // 리포트 재개 시 사전 잔고(최신 cycle_position) 재구성
```
import에 `com.kista.trading.application.service.support.TradingBalanceLoader`, `com.kista.trading.application.service.support.TradingBatchRunState`를 추가한다(`TradingBalanceLoader` 실제 패키지는 `support`).

`executeBatch(contexts, dst)`를 다음으로 교체한다.
```java
    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void executeBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        runState.track(() -> runCloseBatch(contexts, dst));
    }

    private void runCloseBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        LocalDate today = LocalDate.now(TimeZones.KST);
        // 아래 각 조기 반환은 정상 흐름(휴장·시작 전·계산 skip 등)이라 예외/오류 알림 대상이 아니지만,
        // "리포트가 안 왔는데 원인이 안 보이는" 재발 시 로그만으로 중단 지점을 특정하기 위해 사유를 남긴다.
        // 조기 반환도 DONE 기록 — "체크포인트 행 없음 = 미실행"이 성립해야 재개 판정이 cron 누락을 구분한다
        if (contexts.isEmpty()) {
            log.info("매매 배치 중단 — 대상 전략 0건");
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
            return;
        }

        // 시장 개장 여부 확인 (1회) — 모든 전략 공통, 가격 조회 전 조기 반환
        if (!isMarketOpen(today)) {
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
            return;
        }

        // 시작예정일 미도래 사이클 제외
        int contextsBeforeScheduledFilter = contexts.size();
        List<BatchContext> started = filterScheduledStart(contexts, today);
        if (started.isEmpty()) {
            log.info("매매 배치 중단 — 시작예정일 미도래로 대상 {}건 전량 제외", contextsBeforeScheduledFilter);
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
            return;
        }

        // 시작 시점 현재가 + 전일종가 + 기준 매매표(PRIVACY) 일괄 조회 (0회차 진입 방향 판단에 모두 필요)
        TradingPriceFetcher.PriceContext priceCtx = priceFetcher.loadPriceContext(started, today);

        // 슬롯별 후보 수집·예산 배정 — 누락된 AT_CLOSE 슬롯만 PLANNED로 저장
        List<TradingCandidatePlanner.CycleState> states = candidatePlanner.planAll(started, priceCtx.startPriceSnapshots(), priceCtx.privacyBase(), today);
        if (states.isEmpty()) {
            log.warn("매매 배치 중단 — 전략 계산 결과 0건 (대상 {}건 전량 skip, 원인은 위 'plan 후보 생성 오류'/'전략 계산 skip' 로그 참고)",
                    started.size());
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
            return;
        }
        checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLANNED);

        // 공통 대기 — 주문 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트 시 states(증권사 접수 전)는 전부 미처리 — 사용자 알림 대상 (재기동 종료면 재개되므로 제외)
        try {
            waitFor("주문 시각", dst.waitUntilOrderTime(), dst);
        } catch (InterruptedException e) {
            if (!runState.isStopping()) {
                batchGuard.notifyBatchInterrupted(states.stream().map(TradingCandidatePlanner.CycleState::ctx).toList());
            }
            throw e;
        }

        // 증권사 접수 — 임계구역: 재기동 종료 요청이 와도 완료까지 기다린다
        List<CyclePlacedState> placedStates = runState.critical(() -> {
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLACING);
            List<CyclePlacedState> placed = placeAll(states, today);
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.PLACED);
            return placed;
        });

        // 공통 대기 — 마감 시각까지 (모든 전략이 공유하는 단 1회)
        // 이 시점 인터럽트는 사용자 알림 대상 아님 — placedStates는 이미 증권사 접수 완료, 체결 리포트만 지연됨
        waitFor("마감 시각", dst.waitUntilPostClose(), dst);
        marketEventNotifier.notifyMarketClose();

        // 확정 종가 조회 + 리포트 — 임계구역
        runState.critical(() -> {
            // 장 마감 후 확정 종가 일괄 조회 (라이브 현재가 아님 — KIS는 dailyprice, Toss/MOCK은 일봉 캔들 기반 확정 종가)
            Map<StrategyTicker, BigDecimal> closingPrices = priceFetcher.fetchClosingPrices(priceCtx.cycleTickers(), today, priceCtx.priceAccount());
            reportAll(placedStates, closingPrices, today);
            checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
            return null;
        });
    }

    // 재기동 재개 — 접수 완료(PLACED) 이후 중단분: 남은 마감 대기 후 리포트 마커 없는 전략만 리포트
    // 사전 잔고는 최신 cycle_position(리포트 전이므로 계획 시점과 동일), 리포트 대상 주문은 DB의 PLACED 주문
    void resumeCloseReport(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        runState.track(() -> {
            LocalDate today = LocalDate.now(TimeZones.KST);
            List<BatchContext> targets = filterScheduledStart(contexts, today).stream()
                    .filter(ctx -> !batchRunPort.isReported(today, ctx.strategy().id()))
                    .toList();
            log.info("마감 리포트 재개 — 대상 {}건 (전체 {}건)", targets.size(), contexts.size());

            // 마감 전이면 남은 시간만 대기 — 이미 지났으면 마감 알림도 이전 프로세스가 보냈을 수 있어 생략
            if (dst.waitUntilPostClose().isPositive()) {
                waitFor("마감 시각", dst.waitUntilPostClose(), dst);
                marketEventNotifier.notifyMarketClose();
            }
            if (targets.isEmpty()) {
                checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
                return;
            }

            runState.critical(() -> {
                TradingPriceFetcher.PriceContext priceCtx = priceFetcher.loadPriceContext(targets, today);
                Map<StrategyTicker, BigDecimal> closingPrices = priceFetcher.fetchClosingPrices(priceCtx.cycleTickers(), today, priceCtx.priceAccount());
                List<CyclePlacedState> placedStates = new ArrayList<>();
                for (BatchContext ctx : targets) {
                    // 전략별 재구성 실패는 격리 — 다른 전략 리포트는 계속
                    batchGuard.runSafely("리포트 재개 준비", ctx, () -> placedStates.add(new CyclePlacedState(
                            new TradingCandidatePlanner.CycleState(ctx,
                                    balanceLoader.loadBalanceOrThrow(ctx.strategy()).balance(),
                                    null, null, null,
                                    ctx.strategy().isPrivacy() ? priceCtx.privacyBase() : null),
                            orderPort.findPlacedByCycleAndDate(ctx.currentCycle().id(), today))));
                }
                reportAll(placedStates, closingPrices, today);
                checkpoint(TradingBatchJob.CLOSE, today, TradingBatchPhase.DONE);
                return null;
            });
        });
    }
```

`placeOpenOrders`를 다음처럼 바꾼다.
- `placeOpenOrders(contexts)` 기본 경로는 `placeOpenOrders(contexts, DstInfo.forOpenBatch(DstInfo.nextTradeDate()))`를 호출한다.
- 2-인자 버전은 `runState.track(() -> runOpenBatch(contexts, dst));`이다.
- `runOpenBatch` 본문은 기존 `placeOpenOrders(contexts, dst)` 본문에 다음을 적용한 것이다.

```java
    private void runOpenBatch(List<BatchContext> contexts, DstInfo dst) throws InterruptedException {
        LocalDate tradeDate = DstInfo.nextTradeDate(); // 장 개시 스케쥴러 전날 저녁 실행 — 내일이 KST 거래일 (자정 이후 재개면 당일)
        if (contexts.isEmpty()) {
            checkpoint(TradingBatchJob.OPEN, tradeDate, TradingBatchPhase.DONE);
            return;
        }
        log.info("개장 order 생성 + INFINITE 매도 선접수 시작 — 거래일 {}", tradeDate);

        if (!isMarketOpen(tradeDate)) {
            checkpoint(TradingBatchJob.OPEN, tradeDate, TradingBatchPhase.DONE);
            return;
        }

        // 시작예정일 미도래 사이클 제외
        List<BatchContext> started = filterScheduledStart(contexts, tradeDate);
        if (started.isEmpty()) {
            checkpoint(TradingBatchJob.OPEN, tradeDate, TradingBatchPhase.DONE);
            return;
        }

        // 가격 스냅샷 + PRIVACY 기준 매매표 일괄 조회 (개장 전 현시점, 내일 기준 — FIDA가 미리 송신했을 경우)
        TradingPriceFetcher.PriceContext priceCtx = priceFetcher.loadPriceContext(started, tradeDate);

        // 개장 시각까지 대기 — 이 시점 인터럽트 시 contexts 전부가 미처리 — 사용자 알림 대상 (재기동 종료면 재개되므로 제외)
        try {
            waitFor("개장 시각", dst.waitUntilMarketOpen(), dst);
        } catch (InterruptedException e) {
            if (!runState.isStopping()) batchGuard.notifyBatchInterrupted(started);
            throw e;
        }

        // 개장 알림·계획·예산 배정·AT_OPEN 접수 — 임계구역
        runState.critical(() -> {
            checkpoint(TradingBatchJob.OPEN, tradeDate, TradingBatchPhase.PLACING);
            marketEventNotifier.notifyMarketOpen();
            // (기존 본문의 candidates 수집 ~ placeableStates 접수 루프 ~ "완료" 로그를 그대로 옮긴다 — contexts 대신 started 사용)
            checkpoint(TradingBatchJob.OPEN, tradeDate, TradingBatchPhase.DONE);
            return null;
        });
    }
```

괄호 안 주석 부분에는 기존 코드(`List<TradingCandidatePlanner.CyclePlanCandidate> candidates = ...`부터 `log.info("개장 order 생성 + INFINITE 매도 선접수 완료");`까지)를 그대로 옮긴다. 이 블록 안에서 `contexts` 참조는 `started`로 바꾼다. 주석은 남기지 말고 실제 코드로 채운다.

`waitFor`를 교체한다.
```java
    // 지정 시각까지 대기 — DST 정보 로깅 후 sleep(종료 요청 시 즉시 중단), 도달 로그
    private void waitFor(String label, Duration duration, DstInfo dst) throws InterruptedException {
        log.info("DST={}, {}까지 대기: {}ms", dst.isDst(), label, duration.toMillis());
        try {
            runState.sleep(duration);
        } catch (InterruptedException e) {
            // 재기동 종료 요청이면 기동 후 재개 — 그 외(예상 밖 인터럽트)는 PLANNED 미접수 가능 경고
            String message = runState.isStopping()
                    ? "[재기동] " + label + " 대기 중 종료 — 기동 후 재개 예정"
                    : "[스케쥴러 인터럽트] " + label + " 대기 중 강제 종료 — PLANNED 주문 접수 미실행 가능";
            eventPublisher.publishEvent(new TradingErrorEvent(null, message));
            throw e;
        }
        log.info("{} 도달", label);
    }

    // 체크포인트 기록 — 실패해도 배치는 계속(재개 판정만 부정확해짐), 관리자 알림
    private void checkpoint(TradingBatchJob job, LocalDate tradeDate, TradingBatchPhase phase) {
        try {
            batchRunPort.recordPhase(job, tradeDate, phase);
        } catch (RuntimeException e) {
            log.warn("[{}] 배치 체크포인트 {} 기록 실패: {}", job.lockName(), phase, e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[체크포인트] " + job.lockName() + " " + phase + " 기록 실패 — 재기동 시 재개 판정이 부정확할 수 있음"));
        }
    }
```

`TradingExecutionUseCase`에 다음을 추가한다.
```java
    // 재기동 재개 — 마감 배치 접수 완료 이후 중단분 리포트
    void resumeCloseReport(List<BatchContext> contexts) throws InterruptedException;
```
`TradingExecutionFacade`에 다음을 추가한다.
```java
    @Override
    public void resumeCloseReport(List<BatchContext> contexts) throws InterruptedException {
        tradingService.resumeCloseReport(contexts, DstInfo.calculate());
    }
```

- [ ] **Step 7: 통과 확인 (관련 테스트만)**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.application.service.*' --tests 'com.kista.trading.domain.model.DstInfo*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. 기존 `TradingServiceTest` 케이스도 모두 통과해야 한다(`TradingBatchRunState` 실인스턴스는 stopping=false라 기존 흐름 불변).

- [ ] **Step 8: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/application/service/TradingService.java \
  trading-core/src/main/java/com/kista/trading/application/service/CyclePositionPersistor.java \
  trading-core/src/main/java/com/kista/trading/application/usecase/TradingExecutionUseCase.java \
  trading-core/src/main/java/com/kista/trading/application/service/TradingExecutionFacade.java \
  trading-core/src/main/java/com/kista/trading/domain/model/DstInfo.java \
  trading-core/src/test/java/com/kista/trading/application/service/TradingServiceTest.java \
  trading-core/src/test/java/com/kista/trading/application/service/CyclePositionPersistorTest.java \
  trading-core/src/test/java/com/kista/trading/domain/model/DstInfoForOpenBatchTest.java
git commit -m "$(cat <<'EOF'
feat(trading): 매매 배치 단계 체크포인트·임계구역·리포트 재개 경로 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: 스케쥴러 재개 진입점 + `TradingBatchResumer`

**Files:**
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/in/schedule/TradingCloseScheduler.java`
- Modify: `trading-core/src/main/java/com/kista/trading/adapter/in/schedule/TradingOpenScheduler.java`
- Create: `trading-core/src/main/java/com/kista/trading/adapter/in/schedule/TradingBatchResumer.java`
- Test: `trading-core/src/test/java/com/kista/trading/adapter/in/schedule/TradingBatchResumerTest.java`
- Test: `TradingCloseSchedulerTest.java`, `TradingOpenSchedulerTest.java`. 재개 메서드 테스트를 추가한다.

**Interfaces:**
- Consumes:
  - `SchedulerLockService.takeOver` (Task 2)
  - `TradingBatchRunPort.findPhase`, `TradingBatchJob`, `TradingBatchPhase` (Task 1)
  - `TradingExecutionUseCase.resumeCloseReport` (Task 4)
  - `DstInfo.calculate(ZonedDateTime)`, `DstInfo.marketCloseAt()` (Task 4)
- Produces:
  - `TradingCloseScheduler.resume()`, `TradingCloseScheduler.resumeReport()`, `TradingOpenScheduler.resume()`. 모두 `throws InterruptedException`.
  - `TradingBatchResumer.resume(ZonedDateTime nowKst)`, package-private.

- [ ] **Step 1: 스케쥴러 재개 테스트 작성 (실패)**

`TradingCloseSchedulerTest`의 기존 setUp은 `schedulerLockService.tryRun`을 "task 즉시 실행"으로 stub한다. 같은 방식으로 `takeOver`도 lenient stub을 추가한다.
```java
        lenient().doAnswer((Answer<Boolean>) invocation -> {
            SchedulerLockService.LockedTask task = invocation.getArgument(2);
            task.run();
            return true;
        }).when(schedulerLockService).takeOver(anyString(), any(Duration.class), any());
```
테스트:
```java
    @Test
    void resume_takesOverCloseLock_runsFullBatch() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());

        scheduler.resume();

        verify(schedulerLockService).takeOver(eq("trading-close"), eq(Duration.ofHours(3)), any());
        verify(useCase).executeBatch(List.of());
        verify(heartbeatPort).pingClose();
    }

    @Test
    void resumeReport_takesOverCloseLock_runsReportOnly() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());

        scheduler.resumeReport();

        verify(useCase).resumeCloseReport(List.of());
        verify(useCase, never()).executeBatch(any());
        verify(heartbeatPort).pingClose();
    }
```
`TradingOpenSchedulerTest`에도 같은 takeOver stub과 다음 테스트를 추가한다.
```java
    @Test
    void resume_takesOverOpenLock_runsOpenBatch() throws InterruptedException {
        when(strategyPort.findAllActive()).thenReturn(List.of());
        when(contextFactory.buildAll(any())).thenReturn(List.of());

        scheduler.resume();

        verify(schedulerLockService).takeOver(eq("trading-open"), eq(Duration.ofHours(2)), any());
        verify(useCase).placeOpenOrders(List.of());
    }
```

- [ ] **Step 2: 스케쥴러 구현**

`TradingCloseScheduler`에 다음을 추가한다.
```java
    // 재기동 재개 — 이전 프로세스 락을 인수해 마감 배치 전체 재실행 (slot 멱등 재계획, 남은 시간만 대기)
    public void resume() throws InterruptedException {
        schedulerLockService.takeOver("trading-close", Duration.ofHours(3), this::runLocked);
    }

    // 재기동 재개 — 접수 완료(PLACED) 이후 중단분: 리포트 미완료 전략만 리포트
    public void resumeReport() throws InterruptedException {
        schedulerLockService.takeOver("trading-close", Duration.ofHours(3), () -> {
            jobRunner.run("마감 리포트 재개",
                    () -> contextFactory.buildAll(strategyPort.findAllActive()),
                    useCase::resumeCloseReport);
            heartbeatPort.pingClose(); // 인터럽트 시 도달 안 함 — 실행 완료 신호만 발송
        });
    }
```
`TradingOpenScheduler`에 다음을 반영한다.
- `resume()`을 추가한다.
- `runLocked`·`runNow`의 `LocalDate today = LocalDate.now(TimeZones.KST);`를 `LocalDate tradeDate = DstInfo.nextTradeDate();`로 바꾸고, `guardPrivacyStrategies(..., tradeDate)`로 넘긴다. `findTodayTrade`는 KST 거래일 계약이며, 자정 이후 재개 때 날짜 정합을 맞추기 위해서다.
- 파라미터명도 `tradeDate`로 바꾼다.

```java
    // 재기동 재개 — 이전 프로세스 락을 인수해 개장 배치 재실행 (AT_OPEN slot 멱등, 이미 개장했으면 즉시 접수)
    public void resume() throws InterruptedException {
        schedulerLockService.takeOver("trading-open", Duration.ofHours(2), this::runLocked);
    }
```
`TradingOpenSchedulerTest`에 `findTodayTrade(LocalDate.now())`를 기대하는 stub이 있으면 `DstInfo.nextTradeDate()`로 바꾼다. 확인은 `grep -n "findTodayTrade" trading-core/src/test/java/com/kista/trading/adapter/in/schedule/TradingOpenSchedulerTest.java`로 한다.

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.schedule.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Resumer 테스트 작성 (실패)**

`TradingBatchResumerTest.java`. 날짜는 2026-10(미국 DST, 장마감 05:00 KST, 접수 마감 04:50)이다. 10-05가 월요일이다.
```java
package com.kista.trading.adapter.in.schedule;

import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.TradingBatchRunPort;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingBatchResumerTest {

    @Mock TradingBatchRunPort batchRunPort;
    @Mock TradingCloseScheduler closeScheduler;
    @Mock TradingOpenScheduler openScheduler;
    @Mock TradingErrorReportPort errorReportPort;

    TradingBatchResumer resumer;

    private static final LocalDate WED = LocalDate.of(2026, 10, 7);

    @BeforeEach
    void setUp() {
        resumer = new TradingBatchResumer(batchRunPort, closeScheduler, openScheduler, errorReportPort);
    }

    private static ZonedDateTime kst(int month, int day, int hour, int minute) {
        return ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, TimeZones.KST);
    }

    private void phase(TradingBatchJob job, LocalDate date, TradingBatchPhase phase) {
        when(batchRunPort.findPhase(job, date)).thenReturn(Optional.ofNullable(phase));
    }

    @Test
    void close_noRow_beforeCutoff_resumesFullBatch() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);

        resumer.resume(kst(10, 7, 4, 40));

        verify(closeScheduler).resume();
        verify(closeScheduler, never()).resumeReport();
    }

    @Test
    void close_planned_afterCutoff_alertsOnly() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLANNED);

        resumer.resume(kst(10, 7, 4, 55));

        verify(closeScheduler, never()).resume();
        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("재개 불가")));
    }

    @Test
    void close_placing_beforeCutoff_warnsThenResumes() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACING);

        resumer.resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("이중 접수")));
        verify(closeScheduler).resume();
    }

    @Test
    void close_placed_anyTimeInWindow_resumesReport() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.PLACED);

        resumer.resume(kst(10, 7, 10, 0));

        verify(closeScheduler).resumeReport();
        verify(closeScheduler, never()).resume();
    }

    @Test
    void close_done_nothing() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, TradingBatchPhase.DONE);

        resumer.resume(kst(10, 7, 4, 40));

        verifyNoInteractions(closeScheduler, openScheduler, errorReportPort);
    }

    @Test
    void monday_morning_noWindow() {
        resumer.resume(kst(10, 5, 5, 0)); // 월요일 — 마감 배치 없음, 개장 자정 이후 창(화~토)도 아님

        verifyNoInteractions(batchRunPort, closeScheduler, openScheduler);
    }

    @Test
    void open_mondayEvening_noRow_resumesWithTuesdayTradeDate() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resumer.resume(kst(10, 5, 23, 0));

        verify(openScheduler).resume();
    }

    @Test
    void open_afterMidnight_tradeDateIsToday() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), null);

        resumer.resume(kst(10, 6, 0, 30)); // 화 00:30 — 거래일 화

        verify(openScheduler).resume();
    }

    @Test
    void open_done_nothing() throws Exception {
        phase(TradingBatchJob.OPEN, LocalDate.of(2026, 10, 6), TradingBatchPhase.DONE);

        resumer.resume(kst(10, 5, 23, 0));

        verify(openScheduler, never()).resume();
    }

    @Test
    void saturdayEvening_sundayNight_noWindow() {
        resumer.resume(kst(10, 10, 23, 0)); // 토 23:00
        resumer.resume(kst(10, 11, 3, 0));  // 일 03:00

        verifyNoInteractions(batchRunPort, closeScheduler, openScheduler);
    }

    @Test
    void close_resumeThrows_reportedToAdmin() throws Exception {
        phase(TradingBatchJob.CLOSE, WED, null);
        doThrow(new IllegalStateException("boom")).when(closeScheduler).resume();

        resumer.resume(kst(10, 7, 4, 40));

        verify(errorReportPort).reportError(argThat(e -> e.getMessage().contains("boom")));
    }
}
```

- [ ] **Step 4: Resumer 구현**

`TradingBatchResumer.java`:
```java
package com.kista.trading.adapter.in.schedule;

import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.TradingBatchRunPort;
import com.kista.trading.application.port.output.TradingErrorReportPort;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.TradingBatchJob;
import com.kista.trading.domain.model.TradingBatchPhase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

// 기동 시 오늘 거래일의 미완료 매매 배치를 체크포인트 단계부터 재개 — 단일 인스턴스·비겹침 배포 전제(spec 3장)
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", matchIfMissing = true) // 스케쥴러 비활성(local)이면 재개도 비활성
public class TradingBatchResumer {

    private static final LocalTime CLOSE_CRON = LocalTime.of(4, 30);  // TradingCloseScheduler cron 시각
    private static final LocalTime OPEN_CRON = LocalTime.of(22, 30);  // TradingOpenScheduler cron 시각
    private static final Duration CLOSE_PLACEMENT_MARGIN = Duration.ofMinutes(10); // 장마감 10분 전까지만 마감 접수 재개

    private final TradingBatchRunPort batchRunPort;
    private final TradingCloseScheduler closeScheduler;
    private final TradingOpenScheduler openScheduler;
    private final TradingErrorReportPort errorReportPort; // 재개 시작·불가·경고 관리자 알림

    // 기동을 막지 않도록 별도 VT에서 실행 — 재개 배치는 대기를 포함해 길게 돈다
    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        Thread.ofVirtual().name("trading-batch-resumer").start(() -> resume(ZonedDateTime.now(TimeZones.KST)));
    }

    // 마감·개장 판정 창은 겹치지 않아 최대 하나만 실행된다
    void resume(ZonedDateTime nowKst) {
        try {
            resumeClose(nowKst);
            resumeOpen(nowKst);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 재개 도중 다시 종료 — 다음 기동이 이어받음
        } catch (Exception e) {
            log.error("매매 배치 재개 실패: {}", e.getMessage(), e);
            errorReportPort.reportError(e);
        }
    }

    // 화~토 04:30~22:30 — 당일 마감 배치
    private void resumeClose(ZonedDateTime nowKst) throws InterruptedException {
        int day = nowKst.getDayOfWeek().getValue();
        LocalTime time = nowKst.toLocalTime();
        boolean inWindow = day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && !time.isBefore(CLOSE_CRON) && time.isBefore(OPEN_CRON);
        if (!inWindow) return;

        LocalDate tradeDate = nowKst.toLocalDate();
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.CLOSE, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACED) {
            alert("[재개] trading-close 접수 완료 이후 중단 — 리포트 재개 (거래일 " + tradeDate + ")");
            closeScheduler.resumeReport();
            return;
        }
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-close 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ")");
        }
        Instant cutoff = DstInfo.calculate(nowKst).marketCloseAt().minus(CLOSE_PLACEMENT_MARGIN);
        if (!nowKst.toInstant().isBefore(cutoff)) {
            alert("[재개 불가] trading-close 접수 마감 경과 (phase=" + phase + ", 거래일 " + tradeDate + ") — 마감 매매 미접수, 수동 확인 필요");
            return;
        }
        alert("[재개] trading-close 마감 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        closeScheduler.resume();
    }

    // 월~금 22:30~24:00(거래일 익일) 또는 화~토 00:00~04:30(거래일 당일) — DstInfo.nextTradeDate()와 같은 04:30 경계
    private void resumeOpen(ZonedDateTime nowKst) throws InterruptedException {
        int day = nowKst.getDayOfWeek().getValue();
        LocalTime time = nowKst.toLocalTime();
        boolean evening = day <= DayOfWeek.FRIDAY.getValue() && !time.isBefore(OPEN_CRON);
        boolean afterMidnight = day >= DayOfWeek.TUESDAY.getValue() && day <= DayOfWeek.SATURDAY.getValue()
                && time.isBefore(CLOSE_CRON);
        if (!evening && !afterMidnight) return;

        LocalDate tradeDate = evening ? nowKst.toLocalDate().plusDays(1) : nowKst.toLocalDate();
        TradingBatchPhase phase = batchRunPort.findPhase(TradingBatchJob.OPEN, tradeDate).orElse(null);
        if (phase == TradingBatchPhase.DONE) return;
        if (phase == TradingBatchPhase.PLACING) {
            alert("[재개 경고] trading-open 접수 도중 중단 — 이중 접수 여부 확인 필요 (거래일 " + tradeDate + ")");
        }
        alert("[재개] trading-open 개장 배치 재실행 (phase=" + phase + ", 거래일 " + tradeDate + ")");
        openScheduler.resume();
    }

    private void alert(String message) {
        log.warn(message);
        errorReportPort.reportError(new IllegalStateException(message));
    }
}
```

`TradingCloseScheduler`/`TradingOpenScheduler`는 `@ConditionalOnProperty` 빈이다. 둘 다 같은 조건이라 Resumer 주입은 안전하다.

- [ ] **Step 5: 통과 확인**

Run: `bash gradlew :trading-core:test --tests 'com.kista.trading.adapter.in.schedule.*' 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add trading-core/src/main/java/com/kista/trading/adapter/in/schedule/ \
  trading-core/src/test/java/com/kista/trading/adapter/in/schedule/
git commit -m "$(cat <<'EOF'
feat(trading): 기동 시 미완료 매매 배치 재개(TradingBatchResumer) 추가

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 문서 갱신 + 전체 검증 + 로컬 재현

**Files:**
- Modify: `docs/agents/workflow.md`
- Modify: `docs/agents/constraints.md` ("Git 규칙" 매매 가드 항목)
- Modify: `docs/agents/modules/trading.md`
- Modify: `docs/agents/docker-infra.md`. 매매 가드 서술이 있으면 "1번 완료로 제거 가능, 실제 제거는 3번 트랙" 한 줄을 넣는다(`grep -n "가드" docs/agents/docker-infra.md`).

- [ ] **Step 1: workflow.md 갱신**

"`waitFor()` 대기 중 `InterruptedException`" 항목을 다음으로 교체한다.
```markdown
- **재기동 종료·재개** (spec `docs/superpowers/specs/2026-10-02-trading-batch-resume-design.md`):
  - 종료 처리: 배치는 `TradingBatchRunState`에 스레드와 임계구역(마감 `placeAll`·리포트, 개장 계획~AT_OPEN 접수)을 등록한다. 종료 시 `TradingBatchShutdownCoordinator`(SmartLifecycle, 웹 graceful보다 먼저 stop)가 임계구역 완료를 최대 150s 기다린 뒤 대기 구간만 인터럽트한다. 이 경우 사용자 "미접수" 알림(`BatchInterruptedEvent`)은 억제하고 관리자 "[재기동] … 재개 예정"만 보낸다.
  - 체크포인트: 단계는 `trading.trading_batch_run`(job·KST 거래일)에 남는다. 마감은 `PLANNED → PLACING → PLACED → DONE`, 개장은 `PLACING → DONE`이고 조기 반환도 `DONE`으로 기록한다.
  - 리포트 마커: 전략별 리포트 완료는 `trading.trading_batch_report`에 남는다. `CyclePositionPersistor`가 저장 직후 기록하며, 키가 전략인 이유는 rotation 후에도 유효해야 하기 때문이다.
  - 기동 시 재개: `TradingBatchResumer`(ApplicationReadyEvent)가 처리한다.
    - 마감 배치: 행 없음·`PLANNED`·`PLACING`이고 장마감 10분 전 이전이면 전체 재실행한다(`PLACING`은 이중 접수 경고). `PLACED`면 리포트만 재개한다(`resumeCloseReport`). 접수 마감을 지났으면 알림만 보낸다.
    - 개장 배치: 행 없음·`PLACING`이면 재실행한다(`DstInfo.forOpenBatch` — 자정 이후 재개도 지난 개장을 기다리지 않음).
    - 락: 재개는 `SchedulerLockService.takeOver`로 이전 프로세스 락을 인수하고, 자기 프로세스 락은 인수하지 않는다.
  - 전제: **`kista-trading` 단일 인스턴스 + 이전 컨테이너 종료 후 기동(겹침 없음)**.
  - 남은 위험: SIGKILL·OOM·임계구역 150s 초과 시 접수 도중 torn-order(브로커 접수/DB PLANNED·FAILED)가 남을 수 있다. 이 경우 `PLACING` 경고로 수동 확인한다.
```
"병렬 접수 인터럽트 리스크" 항목 끝에 다음 문장을 덧붙인다: "graceful 재기동은 접수 임계구역 완료를 기다리므로 이 위험은 SIGKILL·OOM·150s 초과 시로 한정된다."

- [ ] **Step 2: constraints.md·modules/trading.md·docker-infra.md 갱신**

`constraints.md` "Git 규칙"의 매매 시간대 배포 가드 항목 끝에 다음을 덧붙인다.
```markdown
 재기동 재개(`TradingBatchResumer`, workflow.md "재기동 종료·재개")가 도입돼 이 가드는 제거 가능하다. 실제 제거는 배포 GitOps 재편(3번 트랙)에서 배포 파일과 함께 한다. 제거 후에도 `kista-trading`은 단일 인스턴스·비겹침 배포여야 한다(재개가 잔여 락을 인수하는 전제).
```
`modules/trading.md`의 적절한 섹션(스케쥴러·support 컴포넌트 목록)에 다음을 한 줄씩 추가한다.
- `TradingBatchRunState`·`TradingBatchShutdownCoordinator`(support)
- `TradingBatchResumer`(adapter.in.schedule)
- `TradingBatchRunPort`(+ `trading_batch_run`·`trading_batch_report`)

- [ ] **Step 3: 전체 테스트 1회**

Run: `docker compose up -d postgres && bash gradlew test 2>&1 | grep -E "FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`. 실패하면 `grep -l 'failures="[1-9]' */build/test-results/test/TEST-*.xml build/test-results/test/TEST-*.xml`로 실패 클래스만 확인한다. ArchUnit(`HexagonalArchitectureTest`)·Modulith 위반이면 위치를 고친다.

- [ ] **Step 4: 로컬 재현 1회**

1. `bash gradlew :trading-core:bootJar`
2. 로컬 DB에서 `trading_batch_run`에 오늘 `trading-close` `PLACED` 행을 수동 insert한다. `psql`이나 IntelliJ DB 툴을 쓴다. 테스트 DB가 아닌 로컬 `kistadb`를 쓴다.
3. 화~토 04:30~22:30 KST 시간대라면 commands.md "로컬 2-프로세스 부팅"의 trading-core 실행 명령으로 기동한다(`SPRING_PROFILES_ACTIVE=local`, `scheduler.enabled` 기본 true).
4. 로그에서 `락 인수` → `마감 리포트 재개 — 대상 N건`을 확인하고, 종료 후 `trading_batch_run.phase = DONE`을 확인한다.
5. 시간대 밖이면 이 단계를 건너뛴다. 사유는 최종 보고에 적는다.

로컬 DB의 실제 전략 데이터로 증권사 조회가 일어날 수 있다. local 프로파일 브로커 설정(MOCK 여부)을 먼저 확인하고, 실브로커면 이 단계를 생략해 사용자에게 보고한다.

- [ ] **Step 5: 커밋**

```bash
git add docs/agents/workflow.md docs/agents/constraints.md docs/agents/modules/trading.md docs/agents/docker-infra.md
git commit -m "$(cat <<'EOF'
docs(trading): 매매 배치 재기동 종료·재개 흐름과 가드 제거 조건 문서화

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
)"
```
