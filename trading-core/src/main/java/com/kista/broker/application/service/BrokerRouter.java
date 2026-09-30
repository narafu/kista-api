package com.kista.broker.application.service;

import com.kista.broker.application.port.output.*;
import com.kista.broker.domain.model.*;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 공통 7개 Port 라우터 — account.broker()로 어댑터를 골라 위임 (@Primary라 소비처는 Port 인터페이스만 주입)
// BrokerCapabilitiesPort/BrokerAdapterPort는 구현하지 않는다 — 구현하면 자기 자신이 List<BrokerCapabilitiesPort>에 수집됨
@Primary
@Component
class BrokerRouter implements PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort, ExecutionPort, BrokerPricePort, LiveBalancePort {

    private final Map<Broker, BrokerCapabilitiesPort> adapters;

    BrokerRouter(List<BrokerCapabilitiesPort> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toMap(BrokerAdapterPort::supports, Function.identity()));
    }

    // 지원하지 않으면 IllegalArgumentException — GlobalExceptionHandler → 400
    private BrokerCapabilitiesPort of(BrokerAccountRef account) {
        BrokerCapabilitiesPort adapter = adapters.get(account.broker());
        if (adapter == null) {
            throw new IllegalArgumentException("지원하지 않는 증권사: " + account.broker());
        }
        return adapter;
    }

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return of(account).getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(BrokerAccountRef account) {
        return of(account).getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(BrokerAccountRef account) {
        return of(account).getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(StrategyTicker ticker, BrokerAccountRef account) {
        return of(account).getSellableQuantity(ticker, account);
    }

    @Override
    public void cancel(CancelInstruction instruction, BrokerAccountRef account) {
        of(account).cancel(instruction, account);
    }

    @Override
    public OrderResult place(OrderInstruction instruction, BrokerAccountRef account) {
        return of(account).place(instruction, account);
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account) {
        return of(account).getExecutions(from, to, ticker, account);
    }

    @Override
    public BigDecimal getPrice(StrategyTicker ticker, BrokerAccountRef account) {
        return of(account).getPrice(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return of(account).getPrices(tickers, account);
    }

    @Override
    public PriceSnapshot getPriceSnapshot(StrategyTicker ticker, BrokerAccountRef account) {
        return of(account).getPriceSnapshot(ticker, account);
    }

    @Override
    public Map<StrategyTicker, PriceSnapshot> getPriceSnapshots(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return of(account).getPriceSnapshots(tickers, account);
    }

    @Override
    public BigDecimal getPrevClose(StrategyTicker ticker, BrokerAccountRef account) {
        return of(account).getPrevClose(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return of(account).getPrevCloses(tickers, account);
    }

    @Override
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        return of(account).getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        return of(account).getClosingPrices(tickers, tradeDate, account);
    }

    @Override
    public BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker) {
        return of(account).getLiveBalance(account, ticker);
    }
}
