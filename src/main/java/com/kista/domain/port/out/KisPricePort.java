package com.kista.domain.port.out;

import com.kista.domain.model.Account;

import java.math.BigDecimal;

public interface KisPricePort {
    BigDecimal getPrice(String symbol, Account account);
}
