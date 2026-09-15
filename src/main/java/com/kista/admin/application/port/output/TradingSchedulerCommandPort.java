package com.kista.admin.application.port.output;

// trading-core 스케쥴러 수동 트리거용 아웃바운드 포트 — HTTP 어댑터가 내부 API로 원격 호출한다
public interface TradingSchedulerCommandPort {
    void triggerOpen();
    void triggerClose();
}
