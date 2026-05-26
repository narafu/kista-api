package com.kista.domain.model.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PrivacyTradeBase(
        UUID id,
        BigDecimal avgPrice,
        int holdings,
        List<PrivacyTrade> trades
) {
    public record PrivacyTrade(
            LocalDate tradeDate,       // 거래일
            TradingCycle.Ticker ticker,             // 거래 종목
            Order.OrderType orderType,       // 주문 유형 (LOC/MOC/LIMIT)
            Order.OrderDirection direction,  // 매수/매도 방향
            Integer quantity,          // 주문 수량(nullable)
            BigDecimal price          // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
    ) {
    }
}
