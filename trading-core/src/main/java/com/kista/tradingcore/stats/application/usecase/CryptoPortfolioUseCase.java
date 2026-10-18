package com.kista.tradingcore.stats.application.usecase;

import com.kista.tradingcore.stats.domain.model.CryptoPortfolio;

import java.util.UUID;

// 코인 계좌 포트폴리오 조회 — 주식 계좌는 IllegalArgumentException(400)
public interface CryptoPortfolioUseCase {
    CryptoPortfolio getCryptoPortfolio(UUID accountId, UUID requesterId);
}
