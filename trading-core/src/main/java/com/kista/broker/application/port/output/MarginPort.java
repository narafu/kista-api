package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.MarginItem;

import java.math.BigDecimal;
import java.util.List;

// 증거금 조회 — KIS: TTTC2101R / Toss: buying-power USD+KRW
public interface MarginPort {
    List<MarginItem> getMargin(BrokerAccountRef account);
    BigDecimal getUsdBuyableAmount(BrokerAccountRef account);
}
