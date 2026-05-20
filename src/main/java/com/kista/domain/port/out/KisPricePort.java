package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Ticker;

import java.math.BigDecimal;

public interface KisPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
}
