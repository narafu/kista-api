package com.kista.tradingcore.stats.application.usecase;

import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;

// 증권사 통계 기능(선택 capability) — 현재 지원: TOSS
public interface BrokerStatisticsUseCase {
    // 현재 환율 (USD/KRW) — 계좌 무관 공개 API라 소유권 검증 없이 조회(내부 API용)
    ExchangeRateQuote currentExchangeRate();
}
