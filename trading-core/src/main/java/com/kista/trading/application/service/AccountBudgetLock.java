package com.kista.trading.application.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

// 계좌 BUY 예산 소비 구간 직렬화 — "예약 합계 → live 조회 → PLANNED 커밋"을 같은 계좌끼리 겹치지 않게 한다
// 사용처: 배치 allocator 승인+저장(TradingCandidatePlanner), 수동 실행 승인+저장(ManualTradingService), 접수 직전 재캡(TradingOrderExecutor)
// 접수(placeEach)는 락 밖 — 다른 계좌는 병렬 유지
// ponytail: JVM 내 락 — kista-trading 단일 인스턴스 전제, 다중 인스턴스가 되면 DB 락(계좌 row FOR UPDATE)으로 승격
@Component
class AccountBudgetLock {

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>(); // 계좌 id별 락

    // 락 구간 본문 — 배치 경로의 runSafely가 InterruptedException을 던지므로 그것만 허용
    interface Body<T> {
        T get() throws InterruptedException;
    }

    // 종료 인터럽트 시 락 대기로 stop_grace_period를 잠식하지 않도록 lockInterruptibly
    <T> T call(UUID accountId, Body<T> body) throws InterruptedException {
        ReentrantLock lock = locks.computeIfAbsent(accountId, ignored -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }
}
