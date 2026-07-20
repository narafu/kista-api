package com.kista.application.service.stats;

import com.kista.common.TimeZones;
import com.kista.domain.model.stats.BenchmarkGranularity;
import com.kista.domain.model.stats.InvestmentPoint;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

final class MonthlyReturnCalculator {

    private static final int SCALE = 10; // 수익률과 투자지수 계산 정밀도
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP; // 통계 반올림 기준
    private static final BigDecimal INITIAL_INDEX = new BigDecimal("100.0000000000"); // 최초 투자지수

    List<InvestmentPoint> calculate(List<StrategyCycle> cycles, List<CyclePosition> positions,
                                    LocalDate from, LocalDate to, BenchmarkGranularity granularity) {
        if (cycles.isEmpty() || from.isAfter(to)) {
            return List.of();
        }

        Map<LocalDate, BigDecimal> flows = buildExternalFlows(cycles, positions);
        // 일별 USD 평가액과 외부 현금흐름을 분리한 뒤 시간가중수익률을 계산한다.
        List<DailyValuation> valuations = buildDailyValuations(cycles, positions, from, to, flows);
        return compoundDailyReturns(valuations, flows, granularity);
    }

    private List<DailyValuation> buildDailyValuations(
            List<StrategyCycle> cycles, List<CyclePosition> positions,
            LocalDate from, LocalDate to, Map<LocalDate, BigDecimal> flows) {
        Map<UUID, StrategyCycle> cycleById = cycles.stream()
                .collect(Collectors.toMap(StrategyCycle::id, Function.identity()));
        NavigableMap<LocalDate, Map<UUID, CyclePosition>> positionsByDate = new TreeMap<>();

        // 입력 순서와 무관하게 같은 KST 일자의 사이클별 마지막 스냅샷만 남긴다.
        for (CyclePosition position : positions) {
            if (!cycleById.containsKey(position.strategyCycleId())) {
                continue;
            }
            LocalDate date = position.createdAt().atZone(TimeZones.KST).toLocalDate();
            if (date.isAfter(to)) {
                continue;
            }
            positionsByDate.computeIfAbsent(date, ignored -> new HashMap<>())
                    .merge(position.strategyCycleId(), position, MonthlyReturnCalculator::laterPosition);
        }

        Map<UUID, CyclePosition> latestByCycle = new HashMap<>();
        positionsByDate.headMap(from, false).values().forEach(latestByCycle::putAll);
        Map<UUID, List<StrategyCycle>> cyclesByStrategy = cycles.stream()
                .collect(Collectors.groupingBy(StrategyCycle::strategyId));
        cyclesByStrategy.replaceAll((strategyId, strategyCycles) -> strategyCycles.stream()
                .sorted(MonthlyReturnCalculator::compareCycles)
                .toList());

        List<DailyValuation> valuations = new ArrayList<>();
        boolean hasPreviousValuation = false;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Map<UUID, CyclePosition> dailyPositions = positionsByDate.get(date);
            if (dailyPositions != null) {
                latestByCycle.putAll(dailyPositions);
            }

            // 같은 전략은 교체일의 신규 사이클을 선택하고, 최종 종료일에는 포트폴리오에서 제외한다.
            Map<UUID, StrategyCycle> activeByStrategy = new HashMap<>();
            for (var entry : cyclesByStrategy.entrySet()) {
                StrategyCycle active = activeCycleOn(entry.getValue(), date);
                if (active != null) {
                    activeByStrategy.put(entry.getKey(), active);
                }
            }

            BigDecimal value = BigDecimal.ZERO;
            boolean complete = !activeByStrategy.isEmpty();
            for (StrategyCycle cycle : activeByStrategy.values()) {
                CyclePosition position = latestByCycle.get(cycle.id());
                BigDecimal cycleValue = position == null ? null : assetOf(position);
                if (cycleValue == null) {
                    complete = false;
                    break;
                }
                value = value.add(cycleValue);
            }
            if (complete) {
                valuations.add(new DailyValuation(date, value));
                hasPreviousValuation = true;
            } else if (activeByStrategy.isEmpty()
                    && hasPreviousValuation
                    && flows.getOrDefault(date, BigDecimal.ZERO).signum() < 0) {
                // 활성 전략이 모두 종료되는 음수 흐름일만 0인 교체 후 평가액을 허용한다.
                valuations.add(new DailyValuation(date, BigDecimal.ZERO));
            }
        }
        return valuations;
    }

    private Map<LocalDate, BigDecimal> buildExternalFlows(
            List<StrategyCycle> cycles, List<CyclePosition> positions) {
        Map<LocalDate, BigDecimal> flows = new HashMap<>();
        Map<UUID, List<StrategyCycle>> cyclesByStrategy = cycles.stream()
                .collect(Collectors.groupingBy(StrategyCycle::strategyId));
        Map<UUID, NavigableMap<LocalDate, BigDecimal>> valuesByCycle =
                buildCompleteValuesByCycle(cycles, positions);

        // 전략 최초 진입, 내부 사이클 교체, 최종 종료를 하나의 연속된 외부흐름으로 기록한다.
        for (List<StrategyCycle> strategyCycles : cyclesByStrategy.values()) {
            List<StrategyCycle> ordered = strategyCycles.stream()
                    .sorted(MonthlyReturnCalculator::compareCycles)
                    .toList();
            StrategyCycle first = ordered.getFirst();
            BigDecimal initialValue = firstCompleteValue(valuesByCycle.get(first.id()));
            if (initialValue != null) {
                flows.merge(first.startDate(), initialValue, BigDecimal::add);
            }

            for (int index = 1; index < ordered.size(); index++) {
                StrategyCycle previous = ordered.get(index - 1);
                StrategyCycle next = ordered.get(index);
                if (isContiguousTransition(previous, next)) {
                    BigDecimal previousValue = lastCompleteValue(
                            valuesByCycle.get(previous.id()), next.startDate());
                    BigDecimal nextValue = firstCompleteValue(valuesByCycle.get(next.id()));
                    if (previousValue != null && nextValue != null) {
                        flows.merge(next.startDate(), nextValue.subtract(previousValue), BigDecimal::add);
                    }
                } else {
                    addExitFlow(flows, previous, valuesByCycle.get(previous.id()));
                    addEntryFlow(flows, next, valuesByCycle.get(next.id()));
                }
            }

            StrategyCycle last = ordered.getLast();
            addExitFlow(flows, last, valuesByCycle.get(last.id()));
        }
        return flows;
    }

    private static void addEntryFlow(Map<LocalDate, BigDecimal> flows, StrategyCycle cycle,
                                     NavigableMap<LocalDate, BigDecimal> values) {
        BigDecimal value = firstCompleteValue(values);
        if (value != null) {
            flows.merge(cycle.startDate(), value, BigDecimal::add);
        }
    }

    private static void addExitFlow(Map<LocalDate, BigDecimal> flows, StrategyCycle cycle,
                                    NavigableMap<LocalDate, BigDecimal> values) {
        if (cycle.endDate() == null) {
            return;
        }
        BigDecimal value = lastCompleteValue(values, cycle.endDate());
        if (value != null) {
            flows.merge(cycle.endDate(), value.negate(), BigDecimal::add);
        }
    }

    private Map<UUID, NavigableMap<LocalDate, BigDecimal>> buildCompleteValuesByCycle(
            List<StrategyCycle> cycles, List<CyclePosition> positions) {
        Map<UUID, StrategyCycle> cycleById = cycles.stream()
                .collect(Collectors.toMap(StrategyCycle::id, Function.identity()));
        Map<UUID, NavigableMap<LocalDate, CyclePosition>> dailyPositionsByCycle = new HashMap<>();

        // 같은 날의 마지막 스냅샷이 완전한 경우에만 전략 흐름의 전체 평가값 후보로 사용한다.
        for (CyclePosition position : positions) {
            StrategyCycle cycle = cycleById.get(position.strategyCycleId());
            if (cycle == null) {
                continue;
            }
            LocalDate date = position.createdAt().atZone(TimeZones.KST).toLocalDate();
            if (date.isBefore(cycle.startDate())) {
                continue;
            }
            dailyPositionsByCycle.computeIfAbsent(cycle.id(), ignored -> new TreeMap<>())
                    .merge(date, position, MonthlyReturnCalculator::laterPosition);
        }

        Map<UUID, NavigableMap<LocalDate, BigDecimal>> valuesByCycle = new HashMap<>();
        for (var entry : dailyPositionsByCycle.entrySet()) {
            for (var dailyPosition : entry.getValue().entrySet()) {
                BigDecimal value = assetOf(dailyPosition.getValue());
                if (value != null) {
                    valuesByCycle.computeIfAbsent(entry.getKey(), ignored -> new TreeMap<>())
                            .put(dailyPosition.getKey(), value);
                }
            }
        }
        return valuesByCycle;
    }

    // MONTHLY는 월말 시점 1포인트로 수집(월내 일별 factor 복리), DAILY는 매 유효 평가일마다 1포인트를 방출한다.
    private List<InvestmentPoint> compoundDailyReturns(
            List<DailyValuation> valuations, Map<LocalDate, BigDecimal> flows, BenchmarkGranularity granularity) {
        if (valuations.isEmpty()) {
            return List.of();
        }

        NavigableMap<LocalDate, BigDecimal> orderedFlows = new TreeMap<>(flows);
        Map<YearMonth, InvestmentPoint> monthlyPoints = new LinkedHashMap<>();
        List<InvestmentPoint> dailyPoints = new ArrayList<>();
        BigDecimal investmentIndex = INITIAL_INDEX;
        BigDecimal monthlyFactor = BigDecimal.ONE.setScale(SCALE, ROUNDING_MODE);
        BigDecimal dailyFactor = BigDecimal.ONE.setScale(SCALE, ROUNDING_MODE);
        BigDecimal previousValue = null;
        LocalDate previousDate = null;
        YearMonth currentMonth = null;

        for (DailyValuation valuation : valuations) {
            YearMonth valuationMonth = YearMonth.from(valuation.date());
            if (!valuationMonth.equals(currentMonth)) {
                currentMonth = valuationMonth;
                monthlyFactor = BigDecimal.ONE.setScale(SCALE, ROUNDING_MODE);
            }
            dailyFactor = BigDecimal.ONE.setScale(SCALE, ROUNDING_MODE);

            boolean validIndex = previousValue == null;
            if (previousValue != null) {
                BigDecimal flow = sumFlows(orderedFlows, previousDate, valuation.date());
                if (previousValue.signum() > 0) {
                    BigDecimal valueBeforeEndOfDayFlow = valuation.value().subtract(flow);
                    dailyFactor = valueBeforeEndOfDayFlow.divide(previousValue, SCALE, ROUNDING_MODE);
                    investmentIndex = investmentIndex.multiply(dailyFactor).setScale(SCALE, ROUNDING_MODE);
                    monthlyFactor = monthlyFactor.multiply(dailyFactor).setScale(SCALE, ROUNDING_MODE);
                    validIndex = true;
                }
            }

            // 분모가 유효하지 않은 날도 현재 평가액은 다음 날 비교 기준으로 이어간다.
            previousValue = valuation.value();
            previousDate = valuation.date();
            if (validIndex) {
                if (granularity == BenchmarkGranularity.DAILY) {
                    dailyPoints.add(new InvestmentPoint(
                            valuation.date(),
                            investmentIndex.setScale(SCALE, ROUNDING_MODE),
                            dailyFactor.subtract(BigDecimal.ONE).setScale(SCALE, ROUNDING_MODE)));
                } else {
                    monthlyPoints.put(currentMonth, new InvestmentPoint(
                            currentMonth.atDay(1),
                            investmentIndex.setScale(SCALE, ROUNDING_MODE),
                            monthlyFactor.subtract(BigDecimal.ONE).setScale(SCALE, ROUNDING_MODE)));
                }
            }
        }
        return granularity == BenchmarkGranularity.DAILY
                ? List.copyOf(dailyPoints) : List.copyOf(monthlyPoints.values());
    }

    private static BigDecimal sumFlows(NavigableMap<LocalDate, BigDecimal> flows,
                                       LocalDate previousDate, LocalDate currentDate) {
        return flows.subMap(previousDate, false, currentDate, true).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static StrategyCycle activeCycleOn(List<StrategyCycle> ordered, LocalDate date) {
        StrategyCycle candidate = null;
        int candidateIndex = -1;
        for (int index = 0; index < ordered.size(); index++) {
            StrategyCycle cycle = ordered.get(index);
            if (date.isBefore(cycle.startDate())) {
                break;
            }
            candidate = cycle;
            candidateIndex = index;
        }
        if (candidate == null) {
            return null;
        }

        if (candidate.endDate() == null) {
            return candidate;
        }
        boolean contiguousReplacement = candidateIndex + 1 < ordered.size()
                && isContiguousTransition(candidate, ordered.get(candidateIndex + 1));
        if (!contiguousReplacement) {
            return date.isBefore(candidate.endDate()) ? candidate : null;
        }
        return !date.isAfter(candidate.endDate()) ? candidate : null;
    }

    private static boolean isContiguousTransition(StrategyCycle previous, StrategyCycle next) {
        return previous.endDate() != null && previous.endDate().equals(next.startDate());
    }

    private static BigDecimal firstCompleteValue(NavigableMap<LocalDate, BigDecimal> values) {
        return values == null || values.isEmpty() ? null : values.firstEntry().getValue();
    }

    private static BigDecimal lastCompleteValue(
            NavigableMap<LocalDate, BigDecimal> values, LocalDate date) {
        if (values == null) {
            return null;
        }
        Map.Entry<LocalDate, BigDecimal> entry = values.floorEntry(date);
        return entry == null ? null : entry.getValue();
    }

    private static int compareCycles(StrategyCycle first, StrategyCycle second) {
        int byStartDate = first.startDate().compareTo(second.startDate());
        if (byStartDate != 0) {
            return byStartDate;
        }
        Comparator<java.time.Instant> instantComparator = Comparator.nullsFirst(Comparator.naturalOrder());
        int byCreatedAt = instantComparator.compare(first.createdAt(), second.createdAt());
        if (byCreatedAt != 0) {
            return byCreatedAt;
        }
        return first.id().compareTo(second.id());
    }

    private static CyclePosition laterPosition(CyclePosition first, CyclePosition second) {
        return first.createdAt().isAfter(second.createdAt()) ? first : second;
    }

    private static BigDecimal assetOf(CyclePosition position) {
        if (position.holdings() > 0 && position.closingPrice() == null) {
            return null;
        }
        BigDecimal unitPrice = position.closingPrice() != null ? position.closingPrice() : BigDecimal.ZERO;
        return position.usdDeposit().add(unitPrice.multiply(BigDecimal.valueOf(position.holdings())));
    }

    private record DailyValuation(LocalDate date, BigDecimal value) {}
}
