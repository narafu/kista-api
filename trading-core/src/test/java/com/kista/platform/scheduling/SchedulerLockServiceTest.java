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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배포 가드가 쓰는 "실행 중" 판정(lock_until > now() 이고 완료 기록이 없거나 이번 획득보다 이전)이 락 수명주기와 맞는지 검증
@ContextConfiguration(classes = TradingCoreJpaTestConfig.class)
@Import(SchedulerLockService.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class SchedulerLockServiceTest extends DataJpaTestBase {

    // 배포 가드 SQL과 같은 판정식
    private static final String RUNNING = "SELECT count(*) FROM scheduler_locks l WHERE name = ? AND lock_until > now() "
            + "AND coalesce((to_jsonb(l) ->> 'finished_at')::timestamptz < locked_at, true)";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired SchedulerLockService lockService;

    @Test
    void 실행_중에는_running이고_성공_후에는_락을_유지한_채_running이_아니다() throws InterruptedException {
        var runningDuringTask = new AtomicBoolean();

        boolean ran = lockService.tryRun("test-lock", Duration.ofHours(2),
                () -> runningDuringTask.set(running("test-lock")));

        assertThat(ran).isTrue();
        assertThat(runningDuringTask).isTrue();
        assertThat(running("test-lock")).isFalse();
        // 성공 후에도 TTL까지 락 유지 — 재획득 불가(중복 실행 방지)
        assertThat(lockService.tryRun("test-lock", Duration.ofHours(2), () -> { })).isFalse();
    }

    @Test
    void 만료된_락을_재획득하면_완료_표시가_초기화된다() throws InterruptedException {
        lockService.tryRun("test-lock", Duration.ofHours(2), () -> { });
        jdbcTemplate.update("UPDATE scheduler_locks SET lock_until = now() - interval '1 minute' WHERE name = 'test-lock'");
        var runningDuringTask = new AtomicBoolean();

        lockService.tryRun("test-lock", Duration.ofHours(2), () -> runningDuringTask.set(running("test-lock")));

        assertThat(runningDuringTask).isTrue();
    }

    @Test
    void 실패하면_락이_즉시_해제돼_running이_아니다() {
        assertThatThrownBy(() -> lockService.tryRun("test-lock", Duration.ofHours(2), () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(running("test-lock")).isFalse();
    }

    @Test
    void 구_이미지가_재획득해_완료_기록이_이전_것이면_running으로_본다() throws InterruptedException {
        lockService.tryRun("test-lock", Duration.ofHours(2), () -> { });
        // 구 코드는 재획득 시 finished_at을 리셋하지 않는다 — locked_at만 갱신된 상태를 재현
        jdbcTemplate.update("UPDATE scheduler_locks SET finished_at = now() - interval '1 day' WHERE name = 'test-lock'");

        assertThat(running("test-lock")).isTrue();
    }

    private boolean running(String name) {
        return jdbcTemplate.queryForObject(RUNNING, Integer.class, name) > 0;
    }
}
