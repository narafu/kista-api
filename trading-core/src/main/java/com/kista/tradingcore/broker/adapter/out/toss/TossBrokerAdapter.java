package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.domain.model.*;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.toss.*;
import com.kista.tradingcore.broker.application.port.output.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Toss 증권사 어댑터 — 공통 6개(BrokerCapabilitiesPort) + 통계 2개(BrokerStatisticsPort, 벤더 중립 타입으로 변환해 노출) Port 구현
@Component
@RequiredArgsConstructor
public class TossBrokerAdapter implements BrokerCapabilitiesPort, BrokerStatisticsPort {

    private final TossHoldingsApi tossHoldingsApi;  // portfolio/margin/sellable/exchangeRate/account
    private final TossOrderApi tossOrderApi;          // cancel/place/execution
    private final TossPriceApi tossPriceApi;          // price/snapshot
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
    public CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        return tossOrderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return tossOrderApi.place(instruction, account);
    }

    // --- 통계 Capability (Toss 응답을 벤더 중립 타입으로 변환) ---

    @Override
    public List<BrokerCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        return tossCandleApi.getCandles(symbol, interval, from, to).stream().map(TossCandle::toBrokerCandle).toList();
    }

    @Override
    public List<BrokerCandle> getLatestCandles(String symbol, String interval, int count) {
        return tossCandleApi.getLatestCandles(symbol, interval, count).stream().map(TossCandle::toBrokerCandle).toList();
    }

    @Override
    public List<BrokerCandle> getDailyCandlesBefore(String symbol, LocalDate beforeUsSession, int count) {
        return tossCandleApi.getDailyCandlesBefore(symbol, beforeUsSession, count).stream().map(TossCandle::toBrokerCandle).toList();
    }

    @Override
    public ExchangeRateQuote getExchangeRate() {
        return tossHoldingsApi.getExchangeRate().toExchangeRateQuote();
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

}
