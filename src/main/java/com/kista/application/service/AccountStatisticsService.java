package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.kis.ReservationOrder;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.KisProfitPort;
import com.kista.domain.port.out.KisReservationOrderPort;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import com.kista.domain.port.out.TradingCyclePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountStatisticsService implements GetAccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final TradingCyclePort tradingCyclePort;
    private final TradingCycleHistoryPort tradingCycleHistoryPort;
    private final KisProfitPort kisProfitPort;
    private final KisExecutionPort kisExecutionPort;
    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisReservationOrderPort kisReservationOrderPort;
    private final KisPricePort kisPricePort;

    @Override
    public PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId,
                                               LocalDate from, LocalDate to) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisProfitPort.getPeriodProfit(account, from, to);
    }

    @Override
    public List<Execution> getTrades(UUID accountId, UUID requesterId,
                                      LocalDate from, LocalDate to) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        Ticker ticker = tradingCyclePort.findByAccountId(accountId).stream()
                .filter(c -> c.status() == com.kista.domain.model.tradingcycle.TradingCycle.Status.ACTIVE)
                .findFirst()
                .or(() -> tradingCyclePort.findByAccountId(accountId).stream().findFirst())
                .map(com.kista.domain.model.tradingcycle.TradingCycle::ticker)
                .orElse(null);
        if (ticker == null) return Collections.emptyList();
        return kisExecutionPort.getExecutions(from, to, ticker, account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisPortfolioPort.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisMarginPort.getMargin(account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisDailyTransactionPort.getDailyTransactions(from, to, account);
    }

    @Override
    public List<ReservationOrder> getReservationOrders(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisReservationOrderPort.getReservationOrders(from, to, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        return kisPricePort.getPrices(tickers, account);
    }

    @Override
    public List<AccountCycleHistoryEntry> getCycleHistory(UUID accountId, UUID requesterId,
                                                           LocalDate from, LocalDate to) {
        Account account = accountPort.findByIdOrThrow(accountId);
        account.verifyOwnedBy(requesterId);
        // LocalDate → Instant 변환 (UTC 기준 자정)
        var fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return tradingCycleHistoryPort.findByAccountId(accountId, fromInstant, toInstant);
    }

    @Override
    public List<AccountCycleHistoryEntry> getStrategyCycleHistory(UUID strategyId, UUID requesterId,
                                                                   LocalDate from, LocalDate to) {
        var cycle = tradingCyclePort.findByIdOrThrow(strategyId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);
        var fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        var toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return tradingCycleHistoryPort.findByCycleIdAndDateRange(strategyId, fromInstant, toInstant);
    }

}
