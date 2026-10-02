package com.kista.trading.application.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
            if (batchThread != null) batchThread.interrupt(); // 대기 중 배치가 끝났으면 null
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
