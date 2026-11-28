package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.BrokerCapabilitiesPort;
import com.kista.tradingcore.broker.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

// 메리츠 증권사 어댑터 — 조회는 MeritzTradingApi/MeritzPriceApi, 주문·취소는 MeritzOrderApi로 위임
@Component
@RequiredArgsConstructor
public class MeritzBrokerAdapter implements BrokerCapabilitiesPort {

    private final MeritzTradingApi tradingApi; // 잔고/주문가능/판매가능/체결
    private final MeritzOrderApi orderApi;     // 주문·취소
    private final MeritzPriceApi priceApi;     // 현재가

    @Override
    public Broker supports() {
        return Broker.MERITZ;
    }

    @Override
    public PresentBalanceResult getPresentBalance(BrokerAccountRef account) {
        return tradingApi.getPresentBalance(account);
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
    public Map<StrategyTicker, BigDecimal> currentPrices(BrokerAccountRef account, Collection<StrategyTicker> tickers) {
        return priceApi.getPrices(List.copyOf(tickers), account);
    }
}
