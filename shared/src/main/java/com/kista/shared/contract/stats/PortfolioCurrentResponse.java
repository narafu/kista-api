package com.kista.shared.contract.stats;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

// 전략별 현재 포트폴리오 현황 1건 — GET /api/internal/trading/stats/portfolio/current 응답 (목록)
public record PortfolioCurrentResponse(
        @Nullable StrategyType strategyType,  // 전략 유형
        @Nullable StrategyTicker ticker,      // 거래 종목
        Currency currency,          // 계좌 통화 (USD·USDT·KRW)
        BigDecimal holdings,        // 보유 수량
        @Nullable BigDecimal avgPrice,        // 평단가
        BigDecimal deposit,         // 예수금 (계좌 통화)
        @Nullable BigDecimal closingPrice     // 종가
) {}
