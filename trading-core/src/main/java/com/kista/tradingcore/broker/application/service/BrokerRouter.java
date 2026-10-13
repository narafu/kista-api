package com.kista.tradingcore.broker.application.service;

import com.kista.tradingcore.broker.application.port.output.*;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

// 공통 7개 Port 라우터 — account.broker()로 어댑터를 골라 위임 (@Primary라 소비처는 Port 인터페이스만 주입)
// 전일종가·확정종가는 관리자 시세 우선(AdminMarketPricePort) — 누락 종목만 계좌 기준 어댑터로 조회한다. 현재가는 계좌 어댑터 그대로
// BrokerCapabilitiesPort/BrokerAdapterPort는 구현하지 않는다 — 구현하면 자기 자신이 List<BrokerCapabilitiesPort>에 수집됨
@Primary
@Component
class BrokerRouter implements PortfolioPort, MarginPort, SellableQuantityPort,
        BrokerOrderCorrectionPort, ExecutionPort, BrokerPricePort, LiveBalancePort {

    private final Map<Broker, BrokerCapabilitiesPort> adapters;
    private final AdminMarketPricePort adminPrices; // 관리자 키 시세(Toss → KIS), 부분 결과

    BrokerRouter(List<BrokerCapabilitiesPort> adapters, AdminMarketPricePort adminPrices) {
        this.adminPrices = adminPrices;
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
        // 단건은 관리자 → 계좌 단건 조회 (포트 계약상 null 반환 금지)
        BigDecimal admin = adminPrices.getPrevCloses(List.of(ticker)).get(ticker);
        return admin != null ? admin : of(account).getPrevClose(ticker, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getPrevCloses(List<StrategyTicker> tickers, BrokerAccountRef account) {
        return adminFirst(tickers, adminPrices::getPrevCloses, missing -> of(account).getPrevCloses(missing, account));
    }

    @Override
    public BigDecimal getClosingPrice(StrategyTicker ticker, LocalDate tradeDate, BrokerAccountRef account) {
        BigDecimal admin = adminPrices.getClosingPrices(List.of(ticker), tradeDate).get(ticker);
        return admin != null ? admin : of(account).getClosingPrice(ticker, tradeDate, account);
    }

    @Override
    public Map<StrategyTicker, BigDecimal> getClosingPrices(List<StrategyTicker> tickers, LocalDate tradeDate, BrokerAccountRef account) {
        return adminFirst(tickers, t -> adminPrices.getClosingPrices(t, tradeDate),
                missing -> of(account).getClosingPrices(missing, tradeDate, account));
    }

    // 관리자 시세로 먼저 조회하고 누락 종목만 계좌 어댑터로 보충 — 계좌 조회가 실패하면 기존처럼 예외 전파
    private static Map<StrategyTicker, BigDecimal> adminFirst(
            List<StrategyTicker> tickers,
            Function<List<StrategyTicker>, Map<StrategyTicker, BigDecimal>> admin,
            Function<List<StrategyTicker>, Map<StrategyTicker, BigDecimal>> account) {
        if (tickers.isEmpty()) return Map.of();
        Map<StrategyTicker, BigDecimal> result = new LinkedHashMap<>(admin.apply(tickers));
        List<StrategyTicker> missing = tickers.stream().filter(t -> !result.containsKey(t)).toList();
        if (!missing.isEmpty()) result.putAll(account.apply(missing));
        return result;
    }

    @Override
    public BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker) {
        return of(account).getLiveBalance(account, ticker);
    }
}
