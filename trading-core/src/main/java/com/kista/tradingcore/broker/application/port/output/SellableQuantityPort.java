package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import com.kista.shared.kernel.StrategyTicker;

// 판매 가능 수량 조회 — 미체결 매도를 빼지 않은 보유 수량(소비처가 DB PLANNED/SUBMITTING/PLACED SELL 예약을 직접 뺀다).
// KIS: TTTS3012R ovrs_cblc_qty / Toss: /api/v1/holdings quantity(/sellable-quantity는 미체결 매도 차감 순수량이라 쓰지 않음) / Mock: 최신 포지션
public interface SellableQuantityPort {
    SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account);
}
