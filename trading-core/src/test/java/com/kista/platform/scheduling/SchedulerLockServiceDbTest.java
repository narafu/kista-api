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
            boolean result = lockService.takeOver(LOCK, Duration.ofHours(3), () -> innerRan.set(true));
            assertThat(result).isFalse();
        });

        assertThat(innerRan).isFalse();
    }
}
