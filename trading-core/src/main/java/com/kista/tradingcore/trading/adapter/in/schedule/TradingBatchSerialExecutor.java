package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.shared.platform.scheduling.SchedulerLockService;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

// 개장·마감 배치를 프로세스 안에서 직렬화한다 — TradingBatchRunState의 종료 보호 슬롯이 하나라 조기 마감·재개로 시각이 겹쳐도 동시에 돌지 않게 한다
// 종료 요청 후 대기하던 배치는 TradingBatchRunState.track()이 시작을 거부한다
@Component
class TradingBatchSerialExecutor {
    private final ReentrantLock lock = new ReentrantLock(true); // 대기 순서대로 실행

    void run(SchedulerLockService.LockedTask task) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            task.run();
        } finally {
            lock.unlock();
        }
    }
}
