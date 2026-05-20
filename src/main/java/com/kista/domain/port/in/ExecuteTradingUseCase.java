package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;

public interface ExecuteTradingUseCase {
    void execute(Account account, User user) throws InterruptedException;
}
