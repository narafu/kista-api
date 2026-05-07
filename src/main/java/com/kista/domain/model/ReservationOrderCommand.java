package com.kista.domain.model;

import java.math.BigDecimal;

public record ReservationOrderCommand(
        String symbol,                 // 종목코드 (예: SOXL)
        Order.OrderDirection direction, // 매수/매도
        int qty,                       // 주문수량
        BigDecimal price               // 주문단가 (USD)
) {}
