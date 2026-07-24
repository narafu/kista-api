package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.broker.*;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.broker.*;
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
    public Account.Broker supports() {
        return Account.Broker.KIS;
    }

    // CTRP6504R 결과에 TTTC2101R(margin)에서 예수금·환율 보정
    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
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
    public List<MarginItem> getMargin(Account account) {
        return kisTradingApi.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return kisTradingApi.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return kisTradingApi.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return kisTradingApi.getExecutions(from, to, ticker, account);
    }

    @Override
    public void cancel(com.kista.domain.model.order.Order order, Account account) {
        kisOrderApi.cancel(order, account);
    }

    @Override
    public com.kista.domain.model.order.Order place(com.kista.domain.model.order.Order order, Account account) {
        return kisOrderApi.place(order, account);
    }

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return kisPriceApi.getPrice(ticker, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return kisPriceApi.getPrices(tickers, account);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return kisPriceApi.getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return kisPriceApi.getPriceSnapshots(tickers, account);
    }

    @Override
    public BigDecimal getPrevClose(Ticker ticker, Account account) {
        return kisPriceApi.getPrevClose(ticker, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrevCloses(List<Ticker> tickers, Account account) {
        return kisPriceApi.getPrevCloses(tickers, account);
    }

    @Override
    public BigDecimal getClosingPrice(Ticker ticker, LocalDate tradeDate, Account account) {
        return kisPriceApi.getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getClosingPrices(List<Ticker> tickers, LocalDate tradeDate, Account account) {
        return kisPriceApi.getClosingPrices(tickers, tradeDate, account);
    }

    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return kisTradingApi.getBalance(account, ticker);
    }
}
