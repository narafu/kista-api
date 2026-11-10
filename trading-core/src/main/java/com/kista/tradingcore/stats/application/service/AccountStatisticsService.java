package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.broker.application.service.BrokerCallGuard;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.KstDays;
import com.kista.shared.kernel.Quantities;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.domain.model.DailyTransaction;
import com.kista.tradingcore.broker.domain.model.DailyTransactionResult;
import com.kista.tradingcore.broker.domain.model.PresentBalanceResult;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.CycleHistoryPage;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.application.usecase.AccountStatisticsUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.broker.application.port.output.BrokerPricePort;
import com.kista.tradingcore.broker.application.port.output.PortfolioPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class AccountStatisticsService implements AccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final PortfolioPort portfolioPort;
    private final BrokerPricePort brokerPricePort;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    // 체결기준현재잔고 — KIS: CTRP6504R+TTTC2101R 보정 포함 / Toss: 보유종목+예수금 직접 산출
    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return BrokerCallGuard.wrap("잔고 조회", () ->
                portfolioPort.getPresentBalance(account.toBrokerRef()));
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return toDailyTransactionResult(orderPort.findFilledByAccount(accountId, from, to),
                Map.of(accountId, account.broker().getCurrency()));
    }

    @Override
    public DailyTransactionResult getDailyTransactionsForUser(UUID requesterId, LocalDate from, LocalDate to) {
        // accountId → 계좌 통화 (Broker 견적 통화)
        Map<UUID, Currency> currencies = accountPort.findByUserId(requesterId).stream()
                .collect(Collectors.toMap(Account::id, a -> a.broker().getCurrency()));
        return toDailyTransactionResult(orderPort.findFilledByUser(requesterId, from, to), currencies);
    }

    private DailyTransactionResult toDailyTransactionResult(List<Order> filled, Map<UUID, Currency> currencies) {
        List<DailyTransaction> items = filled.stream()
                .filter(o -> o.filledQuantity() != null && o.filledQuantity().signum() > 0)
                .map(o -> {
                    Currency currency = o.accountId() == null ? null : currencies.get(o.accountId());
                    if (currency == null) throw new IllegalStateException("주문 계좌를 찾을 수 없습니다.");
                    BigDecimal price = o.filledPrice() != null ? o.filledPrice() : o.price();
                    BigDecimal qty = Quantities.normalize(o.filledQuantity());
                    return new DailyTransaction(
                            o.tradeDate().toString(),
                            o.direction(),
                            o.ticker(),
                            o.ticker().name(),
                            qty,
                            price,
                            currency.round(price.multiply(qty)), // 통화별 자릿수 반올림
                            currency
                    );
                })
                .toList();
        return new DailyTransactionResult(items);
    }

    // 전략 생성 화면 티커 목록 가격 — 최소 시드 산정 기준(전일종가)과 동일 소스로 통일
    // (currentPrice로 보여주면 등록 시점마다 미묘하게 다른 값이 최소 시드 기준으로 오인될 수 있음)
    @Override
    public Map<StrategyTicker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<StrategyTicker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return BrokerCallGuard.wrap("전일종가 조회",
                () -> brokerPricePort.getPrevCloses(tickers, account.toBrokerRef()));
    }

    @Override
    public CycleHistoryPage getByAccount(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        Currency currency = accountPort.requireOwnedAccount(accountId, requesterId).broker().getCurrency();
        Instant fromInstant = resolveFrom(from);
        Instant effectiveCursor = cursor != null ? cursor : resolveTo(to);
        List<CyclePositionHistoryEntry> raw =
                cyclePositionPort.findByAccountIdWithCursor(accountId, fromInstant, effectiveCursor, size + 1);
        return toPage(raw.stream().map(e -> e.roundedTo(currency)).toList(), size); // 예수금은 계좌 통화 자릿수로 반올림
    }

    // ── private 헬퍼 ─────────────────────────────────────────────────────────

    private CycleHistoryPage toPage(List<CyclePositionHistoryEntry> raw, int size) {
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        Instant nextCursor = hasMore ? items.getLast().createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    private Instant resolveFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(TimeZones.KST).toInstant() : Instant.EPOCH; // KST 자정 경계
    }

    private Instant resolveTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(clock);
        return KstDays.endExclusive(resolved); // KST 자정 경계 (to 당일 포함)
    }
}
