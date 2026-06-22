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
import com.kista.domain.model.toss.TossSellableQuantity;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
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
