package com.kista.tradingcore.stats.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.domain.model.Order;

// 주문 1건 + 계좌 통화 (관리자 /history — 전 종목·통화별 표기)
public record PortfolioOrder(Order order, Currency currency) {}
