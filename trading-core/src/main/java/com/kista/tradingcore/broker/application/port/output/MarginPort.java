package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;

import java.math.BigDecimal;

// 매수 가능 USD 조회 — Toss: buying-power(USD), Meritz: 주문가능금액 합
public interface MarginPort {
    BigDecimal getUsdBuyableAmount(BrokerAccountRef account);
}
