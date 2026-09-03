package com.kista.broker.domain.model;

import com.kista.domain.model.strategy.Strategy.Ticker;

// 주문 취소 지시 — BrokerOrderCorrectionPort.cancel() 요청
public record CancelInstruction(Ticker ticker, String externalOrderId) {}
