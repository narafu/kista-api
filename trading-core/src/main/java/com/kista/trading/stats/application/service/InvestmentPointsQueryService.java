package com.kista.trading.stats.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.TimeZones;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.stats.application.MonthlyReturnCalculator;
import com.kista.trading.stats.application.usecase.InvestmentPointsQuery;
import com.kista.trading.stats.domain.model.BenchmarkGranularity;
import com.kista.trading.stats.domain.model.InvestmentPoint;
import com.kista.trading.stats.domain.model.InvestmentPointsResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
    private final MonthlyReturnCalculator monthlyReturnCalculator = new MonthlyReturnCalculator();

    // 원본 StatsService.buildInvestmentContext(private)를 그대로 이식 — 소유권 검증까지
    // 이 메서드가 담당하므로(authorizeIfStrategyScope 대체) api 쪽은 사전검증을 하지 않는다.
    @Override
    public InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId,
                                        LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(TimeZones.KST);
        Strategy selectedStrategy = null;
        List<Strategy> strategies;
        if (scope == Scope.STRATEGY) {
            selectedStrategy = strategyPort.findByIdOrThrow(strategyId);
            accountPort.findByIdOrThrow(selectedStrategy.accountId()).verifyOwnedBy(userId);
            strategies = List.of(selectedStrategy);
        } else {
            List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                    .filter(a -> a.broker() != Broker.MOCK)
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
                ? (granularity != BenchmarkGranularity.MONTHLY ? from : from.withDayOfMonth(1))
                : cycles.stream().map(StrategyCycle::startDate).min(LocalDate::compareTo)
                        .orElse(effectiveTo).withDayOfMonth(1);
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
        List<CyclePosition> positions = scope == Scope.STRATEGY
                ? cyclePositionPort.findByStrategyAndRange(strategyId, Instant.EPOCH, toInstant)
                : cyclePositionPort.findByUserAndRange(userId, Instant.EPOCH, toInstant);

        List<InvestmentPoint> investmentPoints = monthlyReturnCalculator.calculate(
                cycles, positions, effectiveFrom, effectiveTo, granularity);

        return new InvestmentPointsResult(investmentPoints, effectiveFrom, effectiveTo, selectedStrategy);
    }
}
