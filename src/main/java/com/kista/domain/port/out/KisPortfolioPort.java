package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.PresentBalanceResult;

public interface KisPortfolioPort {
    PresentBalanceResult getPresentBalance(Account account);
}
