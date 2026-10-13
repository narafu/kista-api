package com.kista.tradingcore.broker.domain.model;

import com.kista.shared.kernel.StrategyTicker;

// 주문 취소 지시 — BrokerOrderCorrectionPort.cancel() 요청
public record CancelInstruction(StrategyTicker ticker, String externalOrderId) {}
