package com.kista.domain.port.out;

import com.kista.domain.model.Account;

import java.time.LocalDate;

public interface KisHolidayPort {
    boolean isMarketOpen(LocalDate date, Account account);
}
