package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PeriodProfitResult;

import java.time.LocalDate;

public interface KisProfitPort {
    PeriodProfitResult getPeriodProfit(Account account, LocalDate from, LocalDate to);
}
