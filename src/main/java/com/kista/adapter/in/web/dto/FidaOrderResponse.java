package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.FidaOrderRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FidaOrderResponse(
        UUID id,                            // 생성된 master record ID
        LocalDate tradeDate,
        Ticker ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        BigDecimal avgPrice,
        int holdings,
        List<Order> orders
) {
    public static FidaOrderResponse of(UUID id, FidaOrderRequest request) {
        return new FidaOrderResponse(
                id,
                request.tradeDate(),
                request.ticker(),
                request.currentCycleStart(),
                request.currentCycleRealizedPnl(),
                request.avgPrice(),
                request.holdings(),
                request.orders()
        );
    }
}
