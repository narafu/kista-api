package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.PeriodProfitResult;

import java.time.LocalDate;

public interface KisProfitPort {
    PeriodProfitResult getPeriodProfit(Account account, LocalDate from, LocalDate to);
}
