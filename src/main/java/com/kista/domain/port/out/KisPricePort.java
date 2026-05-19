package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.Ticker;

import java.math.BigDecimal;

public interface KisPricePort {
    BigDecimal getPrice(Ticker ticker, Account account);
}
