package com.kista.contract.stats;

import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;

// 거래 내역 1건 — GET /api/internal/trading/stats/portfolio/history 응답
public record PortfolioOrderResponse(
        LocalDate tradeDate,        // 거래일
        StrategyTicker ticker,      // 거래 종목
        OrderDirection direction,   // 매매 방향
        OrderType orderType,        // 주문 유형
        Integer quantity,           // 주문 수량 (nullable)
        BigDecimal price            // 주문 가격
) {}
