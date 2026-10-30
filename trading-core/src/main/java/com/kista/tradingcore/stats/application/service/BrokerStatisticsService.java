package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.broker.application.service.BrokerStatisticsPorts;
import com.kista.tradingcore.broker.domain.model.ExchangeRateQuote;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.stats.application.usecase.BrokerStatisticsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class BrokerStatisticsService implements BrokerStatisticsUseCase {

    private static final Broker EXCHANGE_RATE_BROKER = Broker.TOSS; // 환율은 계좌 무관 공개 API — Toss가 유일한 제공자라 고정, 제공자가 늘면 정책으로 승격

    private final BrokerStatisticsPorts statisticsPorts; // 증권사별 통계 포트 레지스트리 — 지원 증권사만 등록됨(현재 TOSS)

    @Override
    public ExchangeRateQuote currentExchangeRate() {
        // 계좌가 없으므로 환율 제공 증권사를 상수로 고정해 통계 포트를 고른다
        return statisticsPorts.find(EXCHANGE_RATE_BROKER)
                .orElseThrow(() -> new IllegalStateException(EXCHANGE_RATE_BROKER + " 통계 포트가 등록되어 있지 않아 환율을 조회할 수 없습니다"))
                .getExchangeRate();
    }
}
