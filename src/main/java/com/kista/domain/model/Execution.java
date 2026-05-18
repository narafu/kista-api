package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Execution(
        LocalDate tradeDate,            // 체결일
        String symbol,                  // 종목 코드
        Order.OrderDirection direction, // 매수/매도 방향
        int qty,                        // 체결 수량
        BigDecimal price,               // 체결 단가 (USD)
        BigDecimal amountUsd,           // 체결 금액 (USD) = price × qty
        String kisOrderId               // KIS 주문 번호 (ODNO)
) {}
