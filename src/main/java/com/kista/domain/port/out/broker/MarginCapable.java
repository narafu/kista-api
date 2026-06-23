package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 증거금 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
public interface MarginCapable {
    List<MarginItem> getMargin(Account account);
    BigDecimal getUsdBuyableAmount(Account account);
}
