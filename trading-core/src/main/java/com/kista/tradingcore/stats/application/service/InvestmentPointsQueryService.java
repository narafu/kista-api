package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.KstDays;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.stats.application.InvestmentIndexCalculator;
import com.kista.tradingcore.stats.application.usecase.InvestmentPointsQuery;
import com.kista.tradingcore.stats.domain.model.InvestmentPoint;
import com.kista.tradingcore.stats.domain.model.InvestmentPointsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class InvestmentPointsQueryService implements InvestmentPointsQuery {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final CycleCapitalEventPort cycleCapitalEventPort; // 투자금 추가·인출 이력 — 외부 흐름 분리·행 복원
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입
    private final InvestmentIndexCalculator investmentIndexCalculator = new InvestmentIndexCalculator();

    // 원본 BenchmarkService.buildInvestmentContext(private)를 그대로 이식 — 소유권 검증까지
    // 이 메서드가 담당하므로(authorizeIfStrategyScope 대체) api 쪽은 사전검증을 하지 않는다.
    @Override
    public InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId, Currency currency,
                                        LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(clock);
        Strategy selectedStrategy = null;
        Currency resultCurrency = currency;
        List<Strategy> strategies;
        if (scope == Scope.STRATEGY) {
            selectedStrategy = strategyPort.findByIdOrThrow(strategyId);
            // 소유 검증으로 읽은 계좌의 통화 — 벤치마크가 투자 통화 표기에 쓴다
            resultCurrency = accountPort.requireOwnedAccount(selectedStrategy.accountId(), userId).broker().getCurrency();
            strategies = List.of(selectedStrategy);
        } else {
            List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                    // 포트폴리오 합산은 통화별로 분리한다 — 요청 통화 계좌만(MOCK 제외)
                    .filter(a -> a.broker() != Broker.MOCK && a.broker().getCurrency() == currency)
                    .map(Account::id)
                    .toList();
            strategies = accountIds.isEmpty() ? List.of() : strategyPort.findByAccountIds(accountIds).values().stream()
                    .flatMap(List::stream)
                    .toList();
        }

        Set<UUID> strategyIds = strategies.stream().map(Strategy::id).collect(Collectors.toSet());
        List<StrategyCycle> cycles = strategyIds.isEmpty()
                ? List.of() : strategyCyclePort.findByStrategyIds(strategyIds);
        LocalDate effectiveFrom = from != null
                ? from
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo).orElse(effectiveTo);
        Instant toInstant = KstDays.endExclusive(effectiveTo);
        List<CyclePosition> rawPositions = scope == Scope.STRATEGY
                ? cyclePositionPort.findByStrategyAndRange(strategyId, Instant.EPOCH, toInstant)
                : cyclePositionPort.findByUserAndRange(userId, Instant.EPOCH, toInstant);

        List<CycleCapitalEvent> events = cycles.isEmpty()
                ? List.of() : cycleCapitalEventPort.findByCycleIds(cycles.stream().map(StrategyCycle::id).toList());
        // in-place로 바뀐 행을 이력으로 원본 복원해 추가·인출이 당일 수익률로 새지 않게 한다
        List<CyclePosition> positions = CapitalLedgerTimeline.restore(rawPositions, events);

        List<InvestmentPoint> investmentPoints = investmentIndexCalculator.calculate(
                cycles, positions, events, effectiveFrom, effectiveTo);

        return new InvestmentPointsResult(investmentPoints, effectiveFrom, effectiveTo, selectedStrategy, resultCurrency);
    }
}
