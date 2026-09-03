package com.kista.notify.application.port.output;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.domain.model.strategy.Strategy;

public interface NotifyPort {
    void notifyMarketClosed();
    void notifyInsufficientBalance(Account account, AccountBalance b, Strategy.Ticker ticker);
    void notifyError(Exception e);
    void notifyInfo(String message); // 스케쥴러 시작/종료 등 일반 정보성 알림
}
