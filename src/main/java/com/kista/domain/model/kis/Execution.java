package com.kista.domain.model.kis;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Execution(
        LocalDate tradeDate,            // 체결일
        Ticker ticker,                  // 종목 코드
        Order.OrderDirection direction, // 매수/매도 방향
        int quantity,                   // 체결 수량
        BigDecimal price,               // 체결 단가 (USD)
        BigDecimal amountUsd,           // 체결 금액 (USD) = price × quantity
        String kisOrderId               // KIS 주문 번호 (ODNO)
) {}
