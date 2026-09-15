package com.kista.notify.application.port.output;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

public interface NotifyPort {
    void notifyMarketClosed();
    void notifyInsufficientBalance(int holdings, BigDecimal usdDeposit, StrategyTicker ticker);
    void notifyError(Exception e);
    void notifyInfo(String message); // 스케쥴러 시작/종료 등 일반 정보성 알림
}
