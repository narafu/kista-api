package com.kista.adapter.in.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
class SchedulerLockService {

    private final JdbcTemplate jdbcTemplate;
    private final String ownerId = buildOwnerId();

    boolean tryRun(String lockName, Duration lockAtMostFor, LockedTask task) throws InterruptedException {
        if (!tryAcquire(lockName, lockAtMostFor)) {
            log.info("[{}] 스케쥴러 락 획득 실패 — 다른 인스턴스가 실행 중", lockName);
            return false;
        }
        // 작업 성공 시 락을 lockAtMostFor 동안 유지 — 즉시 해제하면 스케쥴링 지터로 다른 인스턴스가 순차 획득 가능
        // 작업 실패(예외) 시에만 즉시 해제해 다른 인스턴스가 재시도할 수 있도록 허용
        boolean completed = false;
        try {
            task.run();
            completed = true;
            return true;
        } finally {
            if (!completed) {
                release(lockName);
            }
        }
    }

    private boolean tryAcquire(String lockName, Duration lockAtMostFor) {
        List<String> rows = jdbcTemplate.queryForList("""
                INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by)
                VALUES (?, now() + (? * interval '1 millisecond'), now(), ?)
                ON CONFLICT (name) DO UPDATE
                   SET lock_until = EXCLUDED.lock_until,
                       locked_at = EXCLUDED.locked_at,
                       locked_by = EXCLUDED.locked_by
                 WHERE scheduler_locks.lock_until <= now()
                RETURNING name
                """, String.class, lockName, lockAtMostFor.toMillis(), ownerId);
        return !rows.isEmpty();
    }

    private void release(String lockName) {
        jdbcTemplate.update("""
                UPDATE scheduler_locks
                   SET lock_until = now()
                 WHERE name = ?
                   AND locked_by = ?
                """, lockName, ownerId);
    }

    private static String buildOwnerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ManagementFactory.getRuntimeMXBean().getName();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    @FunctionalInterface
    interface LockedTask {
        void run() throws InterruptedException;
    }
}
