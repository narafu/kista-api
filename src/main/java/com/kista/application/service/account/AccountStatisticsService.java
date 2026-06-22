package com.kista.application.service.account;

import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossAccountInfo;
import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.model.toss.TossExchangeRate;
import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.model.toss.TossStockInfo;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TosCandlePort;
import com.kista.domain.port.out.TossAccountListPort;
import com.kista.domain.port.out.TossExchangeRatePort;
import com.kista.domain.port.out.TossMarketCalendarPort;
import com.kista.domain.port.out.TossStockInfoPort;
import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.application.service.trading.BrokerPriceRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class AccountStatisticsService implements AccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final BrokerExecutionRouter brokerExecutionRouter;
    private final BrokerStatisticsRouter brokerStatisticsRouter;
    private final BrokerPriceRouter brokerPriceRouter;
    // Toss 전용 — Stage 2에서 TossStatisticsService로 이전 예정
    private final TosCandlePort tosCandlePort;
    private final TossStockInfoPort tossStockInfoPort;
    private final TossExchangeRatePort tossExchangeRatePort;
    private final TossMarketCalendarPort tossMarketCalendarPort;
    private final TossAccountListPort tossAccountListPort;

    @Override
    public List<Execution> getExecutions(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) return Collections.emptyList();
        return brokerExecutionRouter.getExecutions(from, to, ticker.get(), account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getMargin(account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getDailyTransactions(accountId, account, from, to);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerPriceRouter.getPrices(tickers, account);
    }

    @Override
    public TossSellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getSellableQuantity(ticker, account);
    }

    @Override
    public List<CyclePositionHistoryEntry> getSnapshotsByAccount(UUID accountId, UUID requesterId,
                                                                  LocalDate from, LocalDate to) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        Instant fromInstant = from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH;
        Instant toInstant = (to != null ? to.plusDays(1) : LocalDate.now(TimeZones.KST).plusDays(1))
                .atStartOfDay(TimeZones.KST).toInstant();
        return cyclePositionPort.findByAccountId(accountId, fromInstant, toInstant);
    }

    @Override
    public CycleHistoryPage getByAccount(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        Instant fromInstant = resolveFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByAccountIdWithCursor(accountId, fromInstant, effectiveCursor, size + 1);
        return toPage(raw, size);
    }

    @Override
    public CycleHistoryPage getByStrategy(UUID strategyId, UUID requesterId,
                                           LocalDate from, LocalDate to,
                                           Instant cursor, int size) {
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        Instant fromInstant = resolveFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByStrategyIdWithCursor(strategyId, fromInstant, effectiveCursor, size + 1);
        return toPage(raw, size);
    }

    // ── Toss 전용 (Stage 2에서 TossStatisticsService로 이전) ──────────────────

    @Override
    public List<TossCandle> getTossCandles(UUID accountId, UUID requesterId, Ticker ticker, String interval,
                                           LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tosCandlePort.getCandles(ticker.name(), interval, from, to);
    }

    @Override
    public TossStockInfo getTossStockInfo(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossStockInfoPort.getStockInfo(ticker);
    }

    @Override
    public TossExchangeRate getTossExchangeRate(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossExchangeRatePort.getExchangeRate();
    }

    @Override
    public List<TossMarketSession> getTossMarketCalendar(UUID accountId, UUID requesterId,
                                                         LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossMarketCalendarPort.getMarketCalendar(from, to);
    }

    @Override
    public List<TossAccountInfo> getTossAccountList(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        if (!account.isToss()) throw new IllegalStateException("Toss 계좌에서만 사용 가능한 기능입니다");
        return tossAccountListPort.getAccountList(account);
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private CycleHistoryPage toPage(List<CyclePositionHistoryEntry> raw, int size) {
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    private Instant resolveFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
    }

    private Instant resolveTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
        return resolved.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
