package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.broker.*;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.*;
import com.kista.domain.port.out.broker.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Toss 증권사 어댑터 — 공통 7개 + Toss 전용 5개 Port 구현
@Component
@RequiredArgsConstructor
public class TossBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort,
        ExecutionPort,
        BrokerPricePort, LiveBalancePort,
        CandlePort, ExchangeRatePort, StockInfoPort,
        BrokerMarketCalendarPort, BrokerAccountPort {

    private final TossHoldingsApi tossHoldingsApi;  // portfolio/margin/sellable/exchangeRate/account
    private final TossOrderApi tossOrderApi;          // cancel/place/execution
    private final TossPriceApi tossPriceApi;          // price/snapshot/stockInfo
    private final TossMarketApi tossMarketApi;         // marketCalendar/accountList
    private final TossCandleApi tossCandleApi;         // candle

    @Override
    public Account.Broker supports() {
        return Account.Broker.TOSS;
    }

    // --- 공통 Capability ---

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        return tossHoldingsApi.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        return tossHoldingsApi.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return tossHoldingsApi.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return tossHoldingsApi.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return tossOrderApi.getExecutions(from, to, ticker, account);
    }

    @Override
    public void cancel(com.kista.domain.model.order.Order order, Account account) {
        tossOrderApi.cancel(order, account);
    }

    @Override
    public com.kista.domain.model.order.Order place(com.kista.domain.model.order.Order order, Account account) {
        return tossOrderApi.place(order, account);
    }

    // --- Toss 전용 Capability ---

    @Override
    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        return tossCandleApi.getCandles(symbol, interval, from, to);
    }

    @Override
    public List<TossCandle> getLatestCandles(String symbol, String interval, int count) {
        return tossCandleApi.getLatestCandles(symbol, interval, count);
    }

    @Override
    public TossExchangeRate getExchangeRate() {
        return tossHoldingsApi.getExchangeRate();
    }

    @Override
    public TossStockInfo getStockInfo(Ticker ticker) {
        return tossPriceApi.getStockInfo(ticker);
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        return tossMarketApi.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(Account account) {
        return tossMarketApi.getAccountList(account);
    }

    // --- BrokerPricePort (공통 API — account 불필요) ---

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return tossPriceApi.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return tossPriceApi.getPrices(tickers); // 공통 API — account 불필요
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return tossPriceApi.getPriceSnapshot(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return tossPriceApi.getPriceSnapshots(tickers); // 공통 API — account 불필요
    }

    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return tossHoldingsApi.getBalance(account, ticker);
    }

}
