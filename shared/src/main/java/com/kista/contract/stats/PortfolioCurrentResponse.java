package com.kista.contract.stats;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;

// 현재 포트폴리오 현황 — GET /api/internal/trading/stats/portfolio/current 응답 (가장 최근 포지션 1건)
public record PortfolioCurrentResponse(
        StrategyTicker ticker,      // 거래 종목
        int holdings,               // 보유 수량
        BigDecimal avgPrice,        // 평단가
        BigDecimal usdDeposit,      // 예수금(USD)
        BigDecimal closingPrice     // 종가
) {}
