package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;

// 전략별 최신 포지션 + 계좌 통화 (관리자 /status — 코인 계좌는 USDT·KRW로 표기)
public record PortfolioPosition(CyclePositionHistoryEntry position, Currency currency) {}
