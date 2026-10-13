package com.kista.broker.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;

// 주문 접수 지시 — BrokerOrderCorrectionPort.place() 요청. trading의 Order에서 증권사가 필요로 하는 필드만 추출해 구성한다
public record OrderInstruction(
        StrategyTicker ticker,
        OrderDirection direction,
        OrderType orderType,
        Integer quantity,
        BigDecimal price
) {}
