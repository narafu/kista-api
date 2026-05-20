package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;

public interface KisAccountPort {
    AccountBalance getBalance(Account account);
}
