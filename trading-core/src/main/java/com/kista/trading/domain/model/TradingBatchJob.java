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
