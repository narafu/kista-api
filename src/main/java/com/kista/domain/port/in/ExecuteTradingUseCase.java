package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import com.kista.domain.model.User;

public interface ExecuteTradingUseCase {
    void execute(Account account, User user) throws InterruptedException;
}
