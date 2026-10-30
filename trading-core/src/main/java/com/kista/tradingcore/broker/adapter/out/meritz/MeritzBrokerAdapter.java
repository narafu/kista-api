package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.BrokerCapabilitiesPort;
import com.kista.tradingcore.broker.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// 메리츠 증권사 어댑터 — 조회는 MeritzTradingApi/MeritzPriceApi, 주문·취소는 MeritzOrderApi로 위임
@Component
@RequiredArgsConstructor
public class MeritzBrokerAdapter implements BrokerCapabilitiesPort {

    private final MeritzTradingApi tradingApi; // 잔고/주문가능/판매가능/체결
    private final MeritzOrderApi orderApi;     // 주문·취소
    private final MeritzPriceApi priceApi;     // 현재가/전일종가/확정종가

    @Override
    public Broker supports() {
        return Broker.MERITZ;
    }

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return tradingApi.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(BrokerAccountRef account) {
        return tradingApi.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return tradingApi.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        return tradingApi.getSellableQuantity(ticker, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account) {
        return tradingApi.getExecutions(from, to, ticker, account);
    }

    @Override
    public CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        return orderApi.cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return orderApi.place(instruction, account);
    }

    @Override
    public BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        return priceApi.getPrice(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return priceApi.getPrices(tickers, account);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account) {
        return priceApi.getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return priceApi.getPriceSnapshots(tickers, account);
    }

    @Override
    public BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account) {
        return priceApi.getPrevClose(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return priceApi.getPrevCloses(tickers, account);
    }

    @Override
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        return priceApi.getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        return priceApi.getClosingPrices(tickers, tradeDate, account);
    }
}
