package com.kista.domain.model.broker;
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
        String externalOrderId          // 증권사 주문 번호 (KIS: ODNO)
) {
    // 관리자 수동 체결 보정용 — amountUsd = price × quantity 내부 계산
    public static Execution ofManualFill(LocalDate tradeDate, Ticker ticker, Order.OrderDirection direction,
                                         int quantity, BigDecimal price, String externalOrderId) {
        return new Execution(tradeDate, ticker, direction, quantity, price,
                price.multiply(BigDecimal.valueOf(quantity)), externalOrderId);
    }
}
