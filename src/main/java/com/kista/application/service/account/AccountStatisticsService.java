package com.kista.application.service.account;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.application.service.broker.BrokerCallGuard;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransaction;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.DailyTransactionSummary;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategySeedPreview;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.BrokerPricePort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
@RequiredArgsConstructor
class AccountStatisticsService implements AccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final BrokerStatisticsRouter brokerStatisticsRouter;
    private final BrokerAdapterRegistry registry;
    private final PrivacyTradePort privacyTradePort;
    private final CycleOrderStrategies cycleStrategies;

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return brokerStatisticsRouter.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return BrokerCallGuard.wrap("예수금 조회", () -> brokerStatisticsRouter.getMargin(account));
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        List<Order> filled = orderPort.findFilledByAccount(accountId, from, to);

        List<DailyTransaction> items = filled.stream()
                .filter(o -> o.filledQuantity() != null && o.filledQuantity() > 0)
                .map(o -> {
                    BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                    int qty = o.filledQuantity();
                    BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
                    return new DailyTransaction(
                            o.tradeDate().toString(),
                            null,
                            o.direction(),
                            o.ticker(),
                            o.ticker().name(),
                            qty,
                            price,
                            amount,
                            null,
                            null,
                            "USD"
                    );
                })
                .toList();

        BigDecimal buyTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.BUY)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = items.stream()
                .filter(t -> t.direction() == Order.OrderDirection.SELL)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DailyTransactionResult(items,
                new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return BrokerCallGuard.wrap("현재가 조회", () -> registry.require(account, BrokerPricePort.class).getPrices(tickers, account));
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

    @Override
    public StrategySeedPreview strategySeedPreview(
            UUID accountId, UUID requesterId,
            Strategy.Type type, Strategy.Ticker ticker, int divisionCount) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        CycleOrderStrategy strategy = cycleStrategies.of(type);

        PrivacyTradeBase privacyBase = strategy.requiresPrivacyBase()
                ? privacyTradePort.findTodayTrade(LocalDate.now(TimeZones.KST)).orElse(null)
                : null;

        if (strategy.requiresPrivacyBase() && privacyBase == null) {
            return new StrategySeedPreview(ticker.name(), null, null, "NO_PRIVACY_BASE");
        }

        BigDecimal price = strategy.requiresPrivacyBase()
                ? null
                : registry.require(account, BrokerPricePort.class).getPrice(ticker, account);
        BigDecimal basePrice = strategy.requiresPrivacyBase()
                ? privacyBase.currentCycleStart()
                : price;
        BigDecimal minSeed = strategy.minRequiredDeposit(price, privacyBase, divisionCount);

        return new StrategySeedPreview(ticker.name(), basePrice, minSeed, null);
    }

    @Override
    public List<Order> getOrdersByStrategy(UUID strategyId, UUID requesterId, LocalDate from, LocalDate to) {
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        return orderPort.findByStrategyId(strategyId, from, to);
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
