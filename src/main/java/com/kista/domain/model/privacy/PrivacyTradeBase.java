package com.kista.domain.model.privacy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PrivacyTradeBase(
        UUID id,
        BigDecimal avgPrice,
        int holdings,
        BigDecimal currentCycleStart, // 현재 사이클 기준가 — PRIVACY multiple 동적 산출 기준
        List<PrivacyTrade> trades
) {
    public PrivacyTradeBase {
        if (currentCycleStart == null || currentCycleStart.signum() <= 0) {
            throw new IllegalStateException("[PRIVACY] currentCycleStart 이상: " + currentCycleStart);
        }
    }

    public record PrivacyTrade(
            LocalDate tradeDate,       // 거래일
            Strategy.Ticker ticker,             // 거래 종목
            Order.OrderType orderType,       // 주문 유형 (LOC/MOC/LIMIT)
            Order.OrderDirection direction,  // 매수/매도 방향
            Integer quantity,          // 주문 수량(nullable)
            BigDecimal price          // 주문 가격 (LOC/MOC는 참고용, 실제 체결가 아님)
    ) {
    }
}
