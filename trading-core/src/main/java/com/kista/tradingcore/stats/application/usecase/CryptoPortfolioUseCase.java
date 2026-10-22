package com.kista.tradingcore.stats.application.usecase;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 코인 계좌 포트폴리오 조회 — 주식 계좌는 IllegalArgumentException(400)
public interface CryptoPortfolioUseCase {
    CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId);

    // 계좌 거래소의 코인 현재가(KRW) — 잔고와 무관(원장 보유 평가 폴백용). 빈 목록·코인 아닌 종목·주식 계좌는 IllegalArgumentException(400), 시세 없는 종목은 결과에서 빠진다
    Map<StrategyTicker, BigDecimal> getCurrentPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers);
}
