package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.shared.kernel.Market;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.math.BigDecimal;
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
    private final OrderPort orderPort;
    private final CycleOrderStrategies cycleOrderStrategies; // 시드 비점유 전략(DCA)의 순투입액을 외부 흐름으로 넘길지 판정
    private final InvestmentIndexCalculator investmentIndexCalculator = new InvestmentIndexCalculator();

    // 원본 BenchmarkService.buildInvestmentContext(private)를 그대로 이식 — 소유권 검증까지
    // 이 메서드가 담당하므로(authorizeIfStrategyScope 대체) api 쪽은 사전검증을 하지 않는다.
    @Override
    public InvestmentPointsResult fetch(UUID userId, Scope scope, UUID strategyId,
                                        LocalDate from, LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(TimeZones.KST);
        Strategy selectedStrategy = null;
        List<Strategy> strategies;
        if (scope == Scope.STRATEGY) {
            selectedStrategy = strategyPort.findByIdOrThrow(strategyId);
            accountPort.findByIdOrThrow(selectedStrategy.accountId()).verifyOwnedBy(userId);
            strategies = List.of(selectedStrategy);
        } else {
            List<UUID> accountIds = accountPort.findByUserId(userId).stream()
                    // 포트폴리오 합산은 통화 구분이 없어 크립토(KRW) 계좌를 뺀다 — 주식 USD와 섞이면 지수가 깨진다(후속: 통화별 분리)
                    .filter(a -> a.broker() != Broker.MOCK && a.broker().getMarket() != Market.CRYPTO_KRW)
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
        Instant toInstant = effectiveTo.plusDays(1).atStartOfDay(TimeZones.KST).toInstant();
        List<CyclePosition> positions = scope == Scope.STRATEGY
                ? cyclePositionPort.findByStrategyAndRange(strategyId, Instant.EPOCH, toInstant)
                : cyclePositionPort.findByUserAndRange(userId, Instant.EPOCH, toInstant);

        // 시드 비점유 전략(DCA) 사이클은 일자별 누적 순투입액을 외부 현금흐름으로 넘긴다 (일괄 조회)
        Map<UUID, Strategy> strategyById = strategies.stream().collect(Collectors.toMap(Strategy::id, s -> s));
        Set<UUID> seedlessCycleIds = cycles.stream()
                .filter(c -> !cycleOrderStrategies.of(strategyById.get(c.strategyId()).type()).occupiesSeed())
                .map(StrategyCycle::id)
                .collect(Collectors.toSet());
        Map<UUID, NavigableMap<LocalDate, BigDecimal>> netInvested = seedlessCycleIds.isEmpty()
                ? Map.of() : orderPort.findFilledNetInvestedByCycleIds(seedlessCycleIds);

        List<InvestmentPoint> investmentPoints = investmentIndexCalculator.calculate(
                cycles, positions, effectiveFrom, effectiveTo, netInvested);

        return new InvestmentPointsResult(investmentPoints, effectiveFrom, effectiveTo, selectedStrategy);
    }
}
