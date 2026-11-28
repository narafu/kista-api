package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.domain.model.*;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// Toss 증권사 어댑터 — 공통 6개(BrokerCapabilitiesPort) + 통계 1개(BrokerStatisticsPort, 벤더 중립 타입으로 변환해 노출) Port 구현
@Component
@RequiredArgsConstructor
public class TossBrokerAdapter implements BrokerCapabilitiesPort, BrokerStatisticsPort {

    private final TossHoldingsApi tossHoldingsApi;  // portfolio/margin/sellable/exchangeRate/account
    private final TossOrderApi tossOrderApi;          // cancel/place/execution
    private final TossPriceApi tossPriceApi;          // 현재가

    @Override
    public Broker supports() {
        return Broker.TOSS;
    }

    // --- 공통 Capability ---

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return tossHoldingsApi.getPresentBalance(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return tossHoldingsApi.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        return tossHoldingsApi.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account) {
        return tossOrderApi.getExecutions(from, to, ticker, account);
    }

    @Override
    public CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        return tossOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return tossOrderApi.place(instruction, account);
    }

    // --- 통계 Capability (Toss 응답을 벤더 중립 타입으로 변환) ---

    @Override
    public ExchangeRateQuote getExchangeRate() {
        return tossHoldingsApi.getExchangeRate().toExchangeRateQuote();
    }

    // --- BrokerPricePort ---

    // 주문 경로 현재가 — 계좌 토큰 /api/v1/prices (Q-13 운영 확인 2026-11-21)
    @Override
    public Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers) {
        return tossPriceApi.currentPrices(account, tickers);
    }
}
