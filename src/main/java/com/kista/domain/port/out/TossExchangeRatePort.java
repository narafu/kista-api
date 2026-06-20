package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossExchangeRate;

public interface TossExchangeRatePort {
    // GET /api/v1/exchange-rate?baseCurrency=USD&quoteCurrency=KRW
    TossExchangeRate getExchangeRate(Account account);
}
