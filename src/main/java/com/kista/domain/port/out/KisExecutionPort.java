package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.time.LocalDate;
import java.util.List;

public interface KisExecutionPort {
    List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account);
}
