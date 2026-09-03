package com.kista.broker.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 증거금 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
public interface MarginPort {
    List<MarginItem> getMargin(Account account);
    BigDecimal getUsdBuyableAmount(Account account);
}
