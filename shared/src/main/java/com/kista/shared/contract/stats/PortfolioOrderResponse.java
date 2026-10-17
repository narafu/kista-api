package com.kista.shared.contract.stats;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;

// 거래 내역 1건 — GET /api/internal/trading/stats/portfolio/history 응답
public record PortfolioOrderResponse(
        LocalDate tradeDate,        // 거래일
        StrategyTicker ticker,      // 거래 종목
        OrderDirection direction,   // 매매 방향
        OrderType orderType,        // 주문 유형
        BigDecimal quantity,        // 주문 수량 (주식 정수 값·코인 소수)
        BigDecimal price            // 주문 가격
) {}
