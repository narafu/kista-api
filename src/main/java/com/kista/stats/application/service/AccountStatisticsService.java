package com.kista.stats.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.application.service.BrokerCallGuard;
import com.kista.common.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.DailyTransaction;
import com.kista.broker.domain.model.DailyTransactionResult;
import com.kista.broker.domain.model.DailyTransactionSummary;
import com.kista.broker.domain.model.MarginItem;
import com.kista.broker.domain.model.PresentBalanceResult;
import com.kista.trading.domain.model.Order;
import com.kista.privacy.domain.model.PrivacyCurrentBase;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.CycleHistoryPage;
import com.kista.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.strategyconfig.domain.model.StrategySeedPreview;
import com.kista.stats.application.usecase.AccountStatisticsUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.broker.application.port.output.BrokerPricePort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import com.kista.sharedkernel.StrategyType;

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
        return toDailyTransactionResult(orderPort.findFilledByAccount(accountId, from, to));
    }

    @Override
    public DailyTransactionResult getDailyTransactionsForUser(UUID requesterId, LocalDate from, LocalDate to) {
        return toDailyTransactionResult(orderPort.findFilledByUser(requesterId, from, to));
    }

    private DailyTransactionResult toDailyTransactionResult(List<Order> filled) {
        List<DailyTransaction> items = filled.stream()
                .filter(o -> o.filledQuantity() != null && o.filledQuantity() > 0)
                .map(o -> {
                    BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                    int qty = o.filledQuantity();
                    BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
                    return new DailyTransaction(
                            o.tradeDate().toString(),
                            null,
                            toDirection(o.direction()),
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
                .filter(t -> t.direction() == com.kista.broker.domain.model.Direction.BUY)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sellTotal = items.stream()
                .filter(t -> t.direction() == com.kista.broker.domain.model.Direction.SELL)
                .map(DailyTransaction::tradeAmountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new DailyTransactionResult(items,
                new DailyTransactionSummary(buyTotal, sellTotal, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static com.kista.broker.domain.model.Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> com.kista.broker.domain.model.Direction.BUY;
            case SELL -> com.kista.broker.domain.model.Direction.SELL;
        };
    }

    // 전략 생성 화면 티커 목록 가격 — 최소 시드 산정 기준(전일종가)과 동일 소스로 통일
    // (currentPrice로 보여주면 등록 시점마다 미묘하게 다른 값이 최소 시드 기준으로 오인될 수 있음)
    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> registry.require(toBrokerRef(account), BrokerPricePort.class).getPrevCloses(tickers, toBrokerRef(account)));
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
            StrategyType type, StrategyTicker ticker, int divisionCount) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);

        // 1단계: 전략 타입별 capability 로드
        CycleOrderStrategy strategy = cycleStrategies.of(type);

        // 2단계: PRIVACY 기준 매매표 조회 — 미리보기는 전일 DB trade_date를 잡지 않도록 스케쥴러 조회와 분리
        PrivacyCurrentBase currentBase = strategy.requiresPrivacyBase()
                ? privacyTradePort.findSeedPreviewBase().orElse(null)
                : null;
        if (strategy.requiresPrivacyBase() && currentBase == null) {
            return new StrategySeedPreview(ticker.name(), null, null, "NO_PRIVACY_BASE");
        }
        // PrivacyCycleOrderStrategy.minRequiredDeposit()은 currentCycleStart만 사용 — avgPrice 접근 없음
        PrivacyTradeBase privacyBase = currentBase != null
                ? new PrivacyTradeBase(null, null, 0, currentBase.currentCycleStart(), List.of())
                : null;

        // 3단계: 기준가 결정 후 최소 시드 계산 — 실제 첫 주문(holdings=0)과 동일하게 전일종가 사용
        BigDecimal price = strategy.requiresPrivacyBase()
                ? null
                : registry.require(toBrokerRef(account), BrokerPricePort.class).getPrevClose(ticker, toBrokerRef(account));
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
        return from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH; // KST 자정 경계
    }

    private Instant resolveTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(TimeZones.KST);
        return resolved.plusDays(1).atStartOfDay(TimeZones.KST).toInstant(); // KST 자정 경계 (to 당일 포함)
    }

    // broker 모듈 순환 방지 — Account → BrokerAccountRef 변환 (broker는 Account를 직접 참조하지 않음)
    // Account.Broker → BrokerAccountRef.Broker는 상수명 byte-identical이라 valueOf(name())으로 매핑
    private static BrokerAccountRef toBrokerRef(Account account) {
        return new BrokerAccountRef(
                account.id(), account.appKey(), account.secretKey(),
                account.accountNo(), account.brokerAccountCode(),
                BrokerAccountRef.Broker.valueOf(account.broker().name()));
    }
}
