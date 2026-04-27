package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.TradingStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class TradingService implements ExecuteTradingUseCase {

    private final String symbol;
    private final KisTokenPort kisTokenPort;
    private final KisHolidayPort kisHolidayPort;
    private final KisAccountPort kisAccountPort;
    private final KisPricePort kisPricePort;
    private final KisOrderPort kisOrderPort;
    private final KisExecutionPort kisExecutionPort;
    private final TradingStrategy tradingStrategy;
    private final CorrectionStrategy correctionStrategy;
    private final TradeHistoryPort tradeHistoryPort;
    private final PortfolioSnapshotPort portfolioSnapshotPort;
    private final NotifyPort notifyPort;

    public TradingService(
            @Value("${kis.symbol:SOXL}") String symbol,
            KisTokenPort kisTokenPort,
            KisHolidayPort kisHolidayPort,
            KisAccountPort kisAccountPort,
            KisPricePort kisPricePort,
            KisOrderPort kisOrderPort,
            KisExecutionPort kisExecutionPort,
            TradingStrategy tradingStrategy,
            CorrectionStrategy correctionStrategy,
            TradeHistoryPort tradeHistoryPort,
            PortfolioSnapshotPort portfolioSnapshotPort,
            NotifyPort notifyPort) {
        this.symbol = symbol;
        this.kisTokenPort = kisTokenPort;
        this.kisHolidayPort = kisHolidayPort;
        this.kisAccountPort = kisAccountPort;
        this.kisPricePort = kisPricePort;
        this.kisOrderPort = kisOrderPort;
        this.kisExecutionPort = kisExecutionPort;
        this.tradingStrategy = tradingStrategy;
        this.correctionStrategy = correctionStrategy;
        this.tradeHistoryPort = tradeHistoryPort;
        this.portfolioSnapshotPort = portfolioSnapshotPort;
        this.notifyPort = notifyPort;
    }

    @Override
    public void execute() throws InterruptedException {
        execute(DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void execute(DstInfo dst) throws InterruptedException {
        long locWaitMs = dst.waitUntilLocDeadline().toMillis();
        if (locWaitMs > 0) Thread.sleep(locWaitMs);

        LocalDate today = LocalDate.now();
        String token = kisTokenPort.getToken();

        if (!kisHolidayPort.isMarketOpen(token, today)) {
            notifyPort.notifyMarketClosed();
            return;
        }

        AccountBalance balance = kisAccountPort.getBalance(token);
        if (balance.shouldSkip()) {
            notifyPort.notifyInsufficientBalance(balance);
            return;
        }

        BigDecimal price = kisPricePort.getPrice(token, symbol);
        TradingVariables vars = tradingStrategy.calculate(balance, price);
        List<Order> mainOrders = placeMainOrders(token, vars, today);

        long postWaitMs = dst.waitUntilPostClose().toMillis();
        if (postWaitMs > 0) Thread.sleep(postWaitMs);

        List<Execution> executions = kisExecutionPort.getExecutions(token, today);
        List<Order> corrections = correctionStrategy.correct(mainOrders, executions, today)
                .stream()
                .map(o -> kisOrderPort.place(token, o))
                .toList();

        mainOrders.forEach(o -> tradeHistoryPort.save(toHistory(o)));
        corrections.forEach(o -> tradeHistoryPort.save(toHistory(o)));

        portfolioSnapshotPort.save(toSnapshot(balance, price, today));
        notifyPort.notifyReport(buildReport(today, vars, mainOrders, corrections, executions));
    }

    private List<Order> placeMainOrders(String token, TradingVariables vars, LocalDate today) {
        List<Order> orders = new ArrayList<>();

        // BUY LOC: 총 포트폴리오의 S% 비중 매수
        int buyQty = vars.b().multiply(vars.s())
                .divide(vars.currentPrice(), 0, RoundingMode.FLOOR)
                .intValue();
        if (buyQty >= 1) {
            orders.add(kisOrderPort.place(token, new Order(
                    today, symbol, Order.OrderType.LOC, Order.OrderDirection.BUY,
                    buyQty, vars.currentPrice(), Order.OrderStatus.PLACED, null, "MAIN")));
        }

        // SELL LOC: 익절 tier(T>0)가 있고 보유 수량 있는 경우 1슬롯 매도
        if (vars.q() > 0 && vars.t() > 0) {
            int sellQty = vars.k().divide(vars.p(), 0, RoundingMode.FLOOR).intValue();
            if (sellQty >= 1) {
                orders.add(kisOrderPort.place(token, new Order(
                        today, symbol, Order.OrderType.LOC, Order.OrderDirection.SELL,
                        sellQty, vars.p(), Order.OrderStatus.PLACED, null, "MAIN")));
            }
        }

        return orders;
    }

    private TradeHistory toHistory(Order o) {
        BigDecimal amountUsd = o.price().multiply(BigDecimal.valueOf(o.qty()))
                .setScale(2, RoundingMode.HALF_UP);
        return new TradeHistory(
                null, o.tradeDate(), o.symbol(), "SOXL_DIVISION",
                o.orderType(), o.direction(), o.qty(), o.price(),
                amountUsd, o.status(), o.kisOrderId(), o.phase(), null);
    }

    private PortfolioSnapshot toSnapshot(AccountBalance balance, BigDecimal price, LocalDate today) {
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(balance.soxlQty()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAsset = marketValue.add(balance.usdDeposit())
                .setScale(2, RoundingMode.HALF_UP);
        return new PortfolioSnapshot(
                null, today, symbol, balance.soxlQty(), balance.avgPrice(),
                price, marketValue, balance.usdDeposit(), totalAsset, null);
    }

    private TradingReport buildReport(LocalDate today, TradingVariables vars,
                                      List<Order> mainOrders, List<Order> corrections,
                                      List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, vars, mainOrders, corrections, totalBought, totalSold);
    }
}
