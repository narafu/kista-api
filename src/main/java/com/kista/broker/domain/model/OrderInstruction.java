package com.kista.broker.domain.model;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

// 주문 접수 지시 — BrokerOrderCorrectionPort.place() 요청. trading의 Order에서 증권사가 필요로 하는 필드만 추출해 구성한다
public record OrderInstruction(
        StrategyTicker ticker,
        Direction direction,
        OrderType orderType,
        Integer quantity,
        BigDecimal price
) {}
