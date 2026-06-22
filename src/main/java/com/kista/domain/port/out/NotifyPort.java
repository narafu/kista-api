package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;

public interface NotifyPort {
    void notifyMarketClosed();
    void notifyInsufficientBalance(Account account, AccountBalance b, Strategy.Ticker ticker);
    void notifyError(Exception e);
    void notifyInfo(String message); // 스케쥴러 시작/종료 등 일반 정보성 알림
}
