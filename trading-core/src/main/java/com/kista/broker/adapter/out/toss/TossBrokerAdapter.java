package com.kista.broker.adapter.out.toss;

import com.kista.broker.domain.model.*;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.*;
import com.kista.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
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
    public Broker supports() {
        return Broker.TOSS;
    }

    // --- 공통 Capability ---

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return tossHoldingsApi.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(BrokerAccountRef account) {
        return tossHoldingsApi.getMargin(account);
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
    public void cancel(CancelInstruction instruction, BrokerAccountRef account) {
        tossOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return tossOrderApi.place(instruction, account);
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
    public TossStockInfo getStockInfo(StrategyTicker ticker) {
        return tossPriceApi.getStockInfo(ticker);
    }

    @Override
    public List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to) {
        return tossMarketApi.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getAccountList(BrokerAccountRef account) {
        return tossMarketApi.getAccountList(account);
    }

    // --- BrokerPricePort (공통 API — account 불필요) ---

    @Override
    public BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        return tossPriceApi.getPrice(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return tossPriceApi.getPrices(tickers); // 공통 API — account 불필요
    }

    @Override
    public PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account) {
        return tossPriceApi.getPriceSnapshot(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return tossPriceApi.getPriceSnapshots(tickers); // 공통 API — account 불필요
    }

    @Override
    public BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account) {
        return tossPriceApi.getPrevClose(ticker); // 공통 API — account 불필요
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return tossPriceApi.getPrevCloses(tickers); // 공통 API — account 불필요
    }

    // tradeDate 일봉 확정 종가 — 라이브 현재가 아님 (TossCandleApi.getCandles 경유, TossPriceApi.getClosingPrice)
    @Override
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        return tossPriceApi.getClosingPrice(ticker, tradeDate); // 공통 API — account 불필요
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>();
        for (StrategyTicker ticker : tickers) {
            result.put(ticker, tossPriceApi.getClosingPrice(ticker, tradeDate));
        }
        return result;
    }

    @Override
    public BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker) {
        return tossHoldingsApi.getBalance(account, ticker);
    }

}
