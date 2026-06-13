package com.kista.application.service.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.DailyTransactionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.kis.MarginItem;
import com.kista.domain.model.kis.PeriodProfitResult;
import com.kista.domain.model.kis.PresentBalanceResult;
import com.kista.domain.model.strategy.CycleHistoryPage;
import com.kista.domain.model.strategy.CyclePositionHistoryEntry;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.KisDailyTransactionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.KisMarginPort;
import com.kista.domain.port.out.KisPortfolioPort;
import com.kista.domain.port.out.KisPricePort;
import com.kista.domain.port.out.KisProfitPort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.TosMarginPort;
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

import static com.kista.domain.model.account.Account.Broker.TOSS;

@Slf4j
@Service
@RequiredArgsConstructor
class AccountStatisticsService implements AccountStatisticsUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final CyclePositionPort cyclePositionPort;
    private final KisProfitPort kisProfitPort;
    private final KisExecutionPort kisExecutionPort;
    private final KisPortfolioPort kisPortfolioPort;
    private final KisMarginPort kisMarginPort;
    private final TosMarginPort tosMarginPort;
    private final KisDailyTransactionPort kisDailyTransactionPort;
    private final KisPricePort kisPricePort;

    @Override
    public PeriodProfitResult getPeriodProfit(UUID accountId, UUID requesterId,
                                               LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        // KIS 예외는 그대로 전파 → 컨트롤러에서 503 처리
        return kisProfitPort.getPeriodProfit(account, from, to);
    }

    @Override
    public List<Execution> getExecutions(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        Optional<Ticker> ticker = strategyPort.findActiveTicker(accountId);
        if (ticker.isEmpty()) return Collections.emptyList();
        return kisExecutionPort.getExecutions(from, to, ticker.get(), account);
    }

    @Override
    public PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return kisPortfolioPort.getPresentBalance(account);
    }

    @Override
    public List<MarginItem> getMargin(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        // Toss 계좌는 KIS API 미지원 — USD·KRW 통화별 매수가능금액 반환
        if (account.broker() == TOSS) {
            return tosMarginPort.getMarginItems(account);
        }
        return kisMarginPort.getMargin(account);
    }

    @Override
    public DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId,
                                                        LocalDate from, LocalDate to) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return kisDailyTransactionPort.getDailyTransactions(from, to, account);
    }

    @Override
    public Map<Ticker, BigDecimal> getPrices(UUID accountId, UUID requesterId, List<Ticker> tickers) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        return kisPricePort.getPrices(tickers, account);
    }

    @Override
    public CycleHistoryPage getByAccount(UUID accountId, UUID requesterId,
                                          LocalDate from, LocalDate to,
                                          Instant cursor, int size) {
        accountPort.requireOwnedAccount(accountId, requesterId);
        Instant fromInstant = resolveFrom(from);
        // cursor 없으면 to(=내일)가 상한 — cursor는 그 이전 지점으로 좁혀감
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

    // size+1 조회 결과로 hasMore 판단 후 CycleHistoryPage 생성
    private CycleHistoryPage toPage(List<CyclePositionHistoryEntry> raw, int size) {
        boolean hasMore = raw.size() > size;
        List<CyclePositionHistoryEntry> items = hasMore ? raw.subList(0, size) : raw;
        // 다음 커서 = 현재 페이지 마지막 항목의 createdAt (DESC 정렬이므로 가장 오래된 것)
        Instant nextCursor = hasMore ? items.get(items.size() - 1).createdAt() : null;
        return new CycleHistoryPage(items, nextCursor, hasMore);
    }

    // null이면 전체 기간 — Epoch(1970-01-01)부터 조회
    private Instant resolveFrom(LocalDate from) {
        return from != null ? from.atStartOfDay(ZoneOffset.UTC).toInstant() : Instant.EPOCH;
    }

    // null이면 오늘 + 1일 (오늘 데이터 포함)
    private Instant resolveTo(LocalDate to) {
        var resolved = to != null ? to : LocalDate.now(); // KST 기준 오늘 (JVM TZ=KST 고정)
        return resolved.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

}
