package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossCommissionRate;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossCommissionsPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossPortfolioPort;
import com.kista.domain.port.out.TossStockInfoPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TosExecutionPort;
import com.kista.domain.port.out.TosMarginPort;
import com.kista.domain.port.out.TossSellableQuantityPort;
import com.kista.domain.port.out.broker.BrokerAccountPort;
import com.kista.domain.port.out.broker.BrokerAdapterPort;
import com.kista.domain.port.out.broker.CandlePort;
import com.kista.domain.port.out.broker.DailyTradePort;
import com.kista.domain.port.out.broker.ExchangeRatePort;
import com.kista.domain.port.out.broker.ExecutionPort;
import com.kista.domain.port.out.broker.MarginPort;
import com.kista.domain.port.out.broker.MarketCalendarPort;
import com.kista.domain.port.out.broker.PortfolioPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.port.out.broker.StockInfoPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Toss 브로커 어댑터 — 공통 5개 + Toss 전용 5개 Port 구현 (BalancePort 미구현 — BrokerAccountRouter 담당)
@Slf4j
@Component
@RequiredArgsConstructor
public class TossBrokerAdapter implements BrokerAdapterPort,
        PortfolioPort, MarginPort, SellableQuantityPort,
        DailyTradePort, ExecutionPort,
        CandlePort, ExchangeRatePort, StockInfoPort,
        MarketCalendarPort, BrokerAccountPort {

    private final TossPortfolioPort tossPortfolioPort;
    private final TosMarginPort tosMarginPort;
    private final TossSellableQuantityPort tossSellableQuantityPort;
    private final TosExecutionPort tosExecutionPort;
    private final TossCommissionsPort tossCommissionsPort;
    private final StrategyPort strategyPort;
    private final TosCandlePort tosCandlePort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

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
        return tosMarginPort.getMargin(account);
    }

    @Override
    public BigDecimal getUsdBuyableAmount(Account account) {
        return tosMarginPort.getUsdBuyableAmount(account);
    }

    @Override
    public SellableQuantity getSellableQuantity(Ticker ticker, Account account) {
        return tossSellableQuantityPort.getSellableQuantity(ticker, account);
    }

    // Toss 체결 내역 + 수수료율로 DailyTransactionResult 조립
    @Override
    public DailyTransactionResult getDailyTransactions(LocalDate from, LocalDate to, Account account) {
        Optional<Ticker> ticker = strategyPort.findActiveTicker(account.id());
        if (ticker.isEmpty()) {
            return new DailyTransactionResult(List.of(), emptySummary());
        }
        List<Execution> executions = tosExecutionPort.getExecutions(from, to, ticker.get(), account);

        // US 수수료율 조회 — 실패 시 0으로 처리 (수수료 미표시)
        BigDecimal usCommissionRate = tossCommissionsPort.getCommissions(account).stream()
                .filter(c -> "US".equals(c.marketCountry()))
                .map(TossCommissionRate::rate)
                .findFirst()
                .orElseGet(() -> {
                    log.warn("Toss US 수수료율 조회 실패 — overseasFee=0으로 처리: accountId={}", account.id());
                    return BigDecimal.ZERO;
                });

        List<DailyTransaction> items = executions.stream()
                .map(e -> new DailyTransaction(
                        e.tradeDate().toString(),
                        null,              // Toss — 결제일 미제공
                        e.direction(),
                        e.ticker(),
                        e.ticker().name(), // Toss — 한글 종목명 미제공
                        e.quantity(),
                        e.price(),
                        e.amountUsd(),
                        BigDecimal.ZERO,   // Toss — KRW 정산금액 미제공
                        BigDecimal.ZERO,   // Toss — 체결 시점 환율 미제공
                        "USD"
                ))
                .toList();

        BigDecimal buyTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.BUY)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = executions.stream()
                .filter(e -> e.direction() == Order.OrderDirection.SELL)
                .map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        // overseasFee = 전체 거래금액 × 수수료율(%) / 100
        BigDecimal overseasFee = buyTotal.add(sellTotal)
                .multiply(usCommissionRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

        return new DailyTransactionResult(items, new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, overseasFee));
    }

    @Override
    public List<Execution> getExecutions(LocalDate from, LocalDate to, Ticker ticker, Account account) {
        return tosExecutionPort.getExecutions(from, to, ticker, account);
    }

    // --- Toss 전용 Capability ---

    @Override
    public List<TossCandle> getCandles(String symbol, String interval, LocalDate from, LocalDate to) {
        return tosCandlePort.getCandles(symbol, interval, from, to);
    }

    @Override
    public List<TossCandle> getLatestCandles(String symbol, String interval, int count) {
        return tosCandlePort.getLatestCandles(symbol, interval, count);
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

    private static DailyTransactionSummary emptySummary() {
        return new DailyTransactionSummary(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
