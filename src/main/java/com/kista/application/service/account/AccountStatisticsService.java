package com.kista.application.service.account;

import com.kista.application.service.trading.BrokerExecutionRouter;
import com.kista.application.service.trading.BrokerPriceRouter;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.StrategyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

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
        try {
            return brokerStatisticsRouter.getMargin(account);
        } catch (Exception e) {
            log.warn("예수금 조회 실패: accountId={}, error={}", accountId, e.getMessage());
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요");
        }
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        try {
            return brokerStatisticsRouter.getDailyTransactions(accountId, account, from, to);
        } catch (Exception e) {
            log.warn("일별 거래내역 조회 실패: accountId={}, error={}", accountId, e.getMessage());
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요");
        }
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        try {
            return brokerPriceRouter.getPrices(tickers, account);
        } catch (Exception e) {
            log.warn("현재가 조회 실패: accountId={}, tickers={}, error={}", accountId, tickers, e.getMessage());
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요");
        }
    }

    @Override
    public SellableQuantity getSellableQuantity(UUID accountId, UUID requesterId, Ticker ticker) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        try {
            return brokerStatisticsRouter.getSellableQuantity(ticker, account);
        } catch (Exception e) {
            log.warn("판매가능수량 조회 실패: accountId={}, ticker={}, error={}", accountId, ticker, e.getMessage());
            throw new IllegalStateException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요");
        }
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
