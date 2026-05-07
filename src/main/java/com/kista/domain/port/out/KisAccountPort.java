package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.AccountBalance;

public interface KisAccountPort {
    AccountBalance getBalance(Account account);
}
