package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.SellableQuantity;
import com.kista.sharedkernel.StrategyTicker;

// 판매 가능 수량 조회 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
public interface SellableQuantityPort {
    SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account);
}
