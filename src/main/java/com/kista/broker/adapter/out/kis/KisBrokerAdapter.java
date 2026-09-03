package com.kista.broker.adapter.out.kis;

import com.kista.broker.domain.model.*;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// KIS 증권사 어댑터 — 공통 7개 Port 구현 (BrokerPricePort + LiveBalancePort 추가)
@Component
@RequiredArgsConstructor
public class KisBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort {

    private final KisTradingApi kisTradingApi; // portfolio/margin/sellable/execution/account
    private final KisOrderApi kisOrderApi;     // cancel/place
    private final KisPriceApi kisPriceApi;     // price/snapshot

    @Override
    public BrokerAccountRef.Broker supports() {
        return BrokerAccountRef.Broker.KIS;
    }

    // CTRP6504R 결과에 TTTC2101R(margin)에서 예수금·환율 보정
    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        PresentBalanceResult portfolio = kisTradingApi.getPresentBalance(account);
        List<MarginItem> margins = kisTradingApi.getMargin(account);
        BigDecimal usdDeposit = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::purchasableAmount)
                .findFirst().orElse(BigDecimal.ZERO);
        BigDecimal rate = margins.stream()
                .filter(m -> m.currency() == Currency.USD)
                .map(MarginItem::usdToKrwRate)
                .findFirst().orElse(BigDecimal.ZERO);
        return new PresentBalanceResult(
                portfolio.items(), portfolio.totalAssetUsd(), portfolio.totalEvalProfit(),
                portfolio.totalReturnRate(), usdDeposit, rate
        );
    }

    @Override
    public List<MarginItem> getMargin(BrokerAccountRef account) {
        return kisTradingApi.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return kisTradingApi.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        return kisTradingApi.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account) {
        return kisTradingApi.getExecutions(from, to, ticker, account);
    }

    @Override
    public void cancel(CancelInstruction instruction, BrokerAccountRef account) {
        kisOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return kisOrderApi.place(instruction, account);
    }

    @Override
    public BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        return kisPriceApi.getPrice(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return kisPriceApi.getPrices(tickers, account);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account) {
        return kisPriceApi.getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return kisPriceApi.getPriceSnapshots(tickers, account);
    }

    @Override
    public BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account) {
        return kisPriceApi.getPrevClose(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return kisPriceApi.getPrevCloses(tickers, account);
    }

    @Override
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        return kisPriceApi.getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        return kisPriceApi.getClosingPrices(tickers, tradeDate, account);
    }

    @Override
    public BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker) {
        return kisTradingApi.getBalance(account, ticker);
    }
}
