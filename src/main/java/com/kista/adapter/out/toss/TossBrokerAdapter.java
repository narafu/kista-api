package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.PriceSnapshot;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.*;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.*;
import com.kista.domain.port.out.broker.MarketCalendarPort;
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
        MarketCalendarPort, BrokerAccountPort {

    private final TossPortfolioPort tossPortfolioPort;
    private final TossMarginPort tossMarginPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;
    private final TossExecutionPort tossExecutionPort;
    private final TossCandlePort tossCandlePort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;
    private final TossOrderPort tossOrderPort;
    private final TossPricePort tossPricePort;         // 현재가·스냅샷 조회 (공통 API — account 불필요)
    private final TossAccountPort tossAccountPort;     // live 잔고 조회

    @Override
    public Account.Broker supports() {
        return Account.Broker.TOSS;
    }

    // --- 공통 Capability ---

    @Override
    public PresentBalanceResult getPresentBalance(Account account) {
        return tossPortfolioPort.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(Account account) {
        return tossMarginPort.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return tossMarginPort.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return tossSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return tossExecutionPort.getExecutions(from, to, ticker, account);
    }

    @Override
    public void cancel(com.kista.domain.model.order.Order order, Account account) {
        tossOrderPort.cancel(order, account);
    }

    @Override
    public com.kista.domain.model.order.Order place(com.kista.domain.model.order.Order order, Account account) {
        return tossOrderPort.place(order, account);
    }

    // --- Toss 전용 Capability ---

    @Override
    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        return tossCandlePort.getCandles(symbol, interval, from, to);
    }

    @Override
    public List<TossCandle> getLatestCandles(String symbol, String interval, int count) {
        return tossCandlePort.getLatestCandles(symbol, interval, count);
    }

    @Override
    public TossExchangeRate getExchangeRate() {
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public TossStockInfo getStockInfo(Ticker ticker) {
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(Account account) {
        return tossAccountListPort.getAccountList(account);
    }

    // --- BrokerPricePort (공통 API — account 불필요) ---

    @Override
    public BigDecimal getPrice(Ticker ticker, Account account) {
        return tossPricePort.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(List<Ticker> tickers, Account account) {
        return tossPricePort.getPrices(tickers); // 공통 API — account 불필요
    }

    @Override
    public PriceSnapshot getPriceSnapshot(Ticker ticker, Account account) {
        return tossPricePort.getPriceSnapshot(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<Ticker, PriceSnapshot> getPriceSnapshots(List<Ticker> tickers, Account account) {
        return tossPricePort.getPriceSnapshots(tickers); // 공통 API — account 불필요
    }

    @Override
    public AccountBalance getLiveBalance(Account account, Ticker ticker) {
        return tossAccountPort.getBalance(account, ticker);
    }

}
