package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;

import java.util.List;

public interface ExecuteTradingUseCase {
    void execute(TradingCycle cycle, Account account, User user) throws InterruptedException;
    void executeBatch(List<BatchContext> contexts) throws InterruptedException;
}
