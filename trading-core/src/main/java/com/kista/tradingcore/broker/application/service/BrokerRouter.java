package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.application.port.output.*;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 공통 6개 Port 라우터 — account.broker()로 어댑터를 골라 위임 (@Primary라 소비처는 Port 인터페이스만 주입)
// 현재가는 계좌 기준 어댑터로 그대로 위임한다
// BrokerCapabilitiesPort/BrokerAdapterPort는 구현하지 않는다 — 구현하면 자기 자신이 List<BrokerCapabilitiesPort>에 수집됨
@Primary
@Component
class BrokerRouter implements PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort, ExecutionPort, BrokerPricePort {

    private final Map<Broker, BrokerCapabilitiesPort> adapters;

    BrokerRouter(List<BrokerCapabilitiesPort> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toMap(BrokerAdapterPort::supports, Function.identity()));
    }

    // 지원하지 않으면 IllegalArgumentException — ProblemDetailExceptionHandler → 400
    private BrokerCapabilitiesPort of(BrokerAccountRef account) {
        BrokerCapabilitiesPort adapter = adapters.get(account.broker());
        if (adapter == null) {
            throw new IllegalArgumentException("지원하지 않는 증권사입니다.");
        }
        return adapter;
    }

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return of(account).getPresentBalance(account);
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
    public CancelOutcome cancel(CancelInstruction instruction, BrokerAccountRef account) {
        return of(account).cancel(instruction, account);
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
    public Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers) {
        return of(account).currentPrices(account, tickers);
    }
}
