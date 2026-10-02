package com.kista.platform.scheduling;

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
public class SchedulerLockService {

    private final JdbcTemplate jdbcTemplate;
    private final String ownerId = buildOwnerId();

    public boolean tryRun(String lockName, Duration lockAtMostFor, LockedTask task) throws InterruptedException {
        if (!tryAcquire(lockName, lockAtMostFor)) {
            log.info("[{}] 스케쥴러 락 획득 실패 — 다른 인스턴스가 실행 중", lockName);
            return false;
        }
        return runAcquired(lockName, task);
    }

    // 재기동 재개 전용 — 죽은 이전 프로세스가 남긴 미만료 락을 인수해 실행한다.
    // 단일 인스턴스·비겹침 배포 전제에서만 안전. 자기 프로세스가 쥔 락(기동 직후 cron 선발화)은 인수하지 않는다
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

    // 획득 시 finished_at을 비워 "실행 중"으로 표시 — 배포 가드가 lock_until > now() AND finished_at IS NULL로 실행 중 배치를 판정한다
    private boolean tryAcquire(String lockName, Duration lockAtMostFor) {
        List<String> rows = jdbcTemplate.queryForList("""
                INSERT INTO scheduler_locks (name, lock_until, locked_at, locked_by, finished_at)
                VALUES (?, now() + (? * interval '1 millisecond'), now(), ?, NULL)
                ON CONFLICT (name) DO UPDATE
                   SET lock_until = EXCLUDED.lock_until,
                       locked_at = EXCLUDED.locked_at,
                       locked_by = EXCLUDED.locked_by,
                       finished_at = NULL
                 WHERE scheduler_locks.lock_until <= now()
                RETURNING name
                """, String.class, lockName, lockAtMostFor.toMillis(), ownerId);
        return !rows.isEmpty();
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

    // 성공 완료 시각 기록 — 락 자체는 TTL까지 유지(중복 실행 방지)하고 완료 사실만 남긴다.
    // 기록 실패가 이미 끝난 배치를 실패로 보이게 하면 안 되므로 경고만 남긴다(배포 가드가 TTL까지 보수적으로 막을 뿐)
    private void markFinished(String lockName) {
        try {
            jdbcTemplate.update("""
                    UPDATE scheduler_locks
                       SET finished_at = now()
                     WHERE name = ?
                       AND locked_by = ?
                    """, lockName, ownerId);
        } catch (RuntimeException e) {
            log.warn("[{}] 스케쥴러 완료 시각 기록 실패 — 배치는 정상 완료", lockName, e);
        }
    }

    private void release(String lockName) {
        jdbcTemplate.update("""
                UPDATE scheduler_locks
                   SET lock_until = now()
                 WHERE name = ?
                   AND locked_by = ?
                """, lockName, ownerId);
    }

    // 프로세스 실행마다 고유 — docker restart처럼 hostname·pid가 같은 재기동도 이전 프로세스와 구분돼야 takeOver가 성립
    private static String buildOwnerId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ManagementFactory.getRuntimeMXBean().getName() + "-" + suffix;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    @FunctionalInterface
    public interface LockedTask {
        void run() throws InterruptedException;
    }
}
