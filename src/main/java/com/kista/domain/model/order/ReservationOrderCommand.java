package com.kista.domain.model.order;
import com.kista.domain.model.strategy.Ticker;

import java.math.BigDecimal;

public record ReservationOrderCommand(
        Ticker ticker,                 // 거래 종목
        Order.OrderDirection direction, // 매수/매도
        int quantity,                  // 주문수량
        BigDecimal price               // 주문단가 (USD)
) {}
