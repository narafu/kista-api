package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;

import java.math.BigDecimal;

public interface TosMarginPort {
    // GET /api/v1/orders/buyable-amount — USD 매수가능금액 조회
    BigDecimal getBuyableAmount(Account account);
}
