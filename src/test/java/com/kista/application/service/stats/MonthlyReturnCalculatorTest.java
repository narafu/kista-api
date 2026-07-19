package com.kista.application.service.stats;

import com.kista.common.TimeZones;
import com.kista.domain.model.stats.MonthlyInvestmentPoint;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.StrategyCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MonthlyReturnCalculatorTest {

    private static final LocalDate JANUARY_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JANUARY_31 = LocalDate.of(2026, 1, 31);
    private static final LocalDate FEBRUARY_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEBRUARY_28 = LocalDate.of(2026, 2, 28);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);

    private final MonthlyReturnCalculator calculator = new MonthlyReturnCalculator();

    @Test
    void 단일_사이클의_월말_USD_평가액으로_누적지수를_계산한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"), position(cycle, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result)
                .extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .containsExactly(new BigDecimal("100.0000000000"), new BigDecimal("110.0000000000"));
    }

    @Test
    void 사이클_교체의_추가_투입금은_수익으로_계산하지_않는다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "100", JANUARY_1, JANUARY_31);
        StrategyCycle next = activeCycle(strategyId, "150", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(next, FEBRUARY_1, "150"),
                        position(next, FEBRUARY_28, "150")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(MonthlyInvestmentPoint::monthlyReturn)
                .isEqualTo(new BigDecimal("0.0000000000"));
    }

    @Test
    void 같은_날_교체된_사이클의_전액_재투자는_중복_평가하지_않는다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "110", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "110", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "110"),
                        position(previous, FEBRUARY_1, "110"),
                        position(next, FEBRUARY_1, "110"),
                        position(next, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(MonthlyInvestmentPoint::monthlyReturn)
                .isEqualTo(new BigDecimal("0.0000000000"));
    }

    @Test
    void 사이클_교체의_일부_회수는_누적지수를_낮추지_않는다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "110", "110", JANUARY_1, JANUARY_31);
        StrategyCycle next = activeCycle(strategyId, "90", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "110"),
                        position(next, FEBRUARY_1, "90"),
                        position(next, FEBRUARY_28, "90")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("100.0000000000"));
    }

    @Test
    void 여러_전략은_일별_포트폴리오로_합산한_뒤_수익률을_계산한다() {
        StrategyCycle small = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle large = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(small, large),
                List.of(position(small, JANUARY_31, "100"),
                        position(large, JANUARY_31, "900"),
                        position(small, FEBRUARY_28, "200"),
                        position(large, FEBRUARY_28, "900")),
                JANUARY_1, FEBRUARY_28);

        // 전략별 수익률 평균 50%가 아니라 합산 자산 1,000 -> 1,100의 10%다.
        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 같은_날_같은_사이클은_시간상_마지막_스냅샷을_사용한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        CyclePosition earlier = position(cycle, FEBRUARY_28, LocalTime.of(10, 0), "120");
        CyclePosition later = position(cycle, FEBRUARY_28, LocalTime.of(11, 0), "110");

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"), later, earlier),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 스냅샷이_없는_날과_달은_직전_평가액을_이어_사용한다() {
        StrategyCycle first = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle second = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(first, second),
                List.of(position(first, JANUARY_31, "100"),
                        position(second, JANUARY_31, "900"),
                        position(second, FEBRUARY_28, "990")),
                JANUARY_1, MARCH_31);

        assertThat(result)
                .extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .containsExactly(new BigDecimal("100.0000000000"),
                        new BigDecimal("109.0000000000"),
                        new BigDecimal("109.0000000000"));
    }

    @Test
    void 종료된_전략은_종료일_다음날부터_포트폴리오에서_제외한다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, JANUARY_31);
        StrategyCycle active = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(ended, active),
                List.of(position(ended, JANUARY_31, "100"),
                        position(active, JANUARY_31, "100"),
                        position(active, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        // 종료금 100 회수 후 남은 전략 100 -> 110의 수익률은 10%다.
        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 개별_전략_시계열은_마지막_사이클의_종료월에서_끝난다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, JANUARY_31);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(ended), List.of(position(ended, JANUARY_31, "100")),
                JANUARY_1, MARCH_31);

        assertThat(result)
                .extracting(MonthlyInvestmentPoint::baseMonth)
                .containsExactly(JANUARY_1);
    }

    @Test
    void 외부흐름_반영_후_분모가_0_이하면_그날만_생략한다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "200", JANUARY_1, JANUARY_31);
        StrategyCycle started = activeCycle(UUID.randomUUID(), "50", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(ended, started),
                List.of(position(ended, JANUARY_31, "100"),
                        position(started, FEBRUARY_1, "50"),
                        position(started, FEBRUARY_28, "55")),
                JANUARY_1, FEBRUARY_28);

        // 2월 1일 분모는 100 - 200 + 50 = -50이므로 생략하고, 다음 평가의 기준은 50으로 갱신한다.
        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 월별_포인트는_그달의_마지막_유효_누적지수를_사용한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, LocalDate.of(2026, 1, 10), "100"),
                        position(cycle, LocalDate.of(2026, 1, 20), "120")),
                JANUARY_1, JANUARY_31);

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point.baseMonth()).isEqualTo(JANUARY_1);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("120.0000000000"));
        });
    }

    @Test
    void MDD는_월중_저점이_아닌_월말_USD_지수만_사용한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        List<CyclePosition> positions = new ArrayList<>(List.of(
                position(cycle, JANUARY_1, "100"),
                position(cycle, LocalDate.of(2026, 1, 15), "50"),
                position(cycle, JANUARY_31, "100"),
                position(cycle, FEBRUARY_28, "80"),
                position(cycle, MARCH_31, "120")));

        List<MonthlyInvestmentPoint> points = calculator.calculate(
                List.of(cycle), positions, JANUARY_1, MARCH_31);

        assertThat(points)
                .extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .containsExactly(new BigDecimal("100.0000000000"),
                        new BigDecimal("80.0000000000"),
                        new BigDecimal("120.0000000000"));
        assertThat(calculator.calculateMaxDrawdown(points))
                .isEqualTo(new BigDecimal("-0.2000000000"));
    }

    private static StrategyCycle activeCycle(UUID strategyId, String startAmount, LocalDate startDate) {
        return cycle(strategyId, startAmount, null, startDate, null);
    }

    private static StrategyCycle closedCycle(UUID strategyId, String startAmount, String endAmount,
                                             LocalDate startDate, LocalDate endDate) {
        return cycle(strategyId, startAmount, endAmount, startDate, endDate);
    }

    private static StrategyCycle cycle(UUID strategyId, String startAmount, String endAmount,
                                       LocalDate startDate, LocalDate endDate) {
        return new StrategyCycle(UUID.randomUUID(), strategyId, null,
                new BigDecimal(startAmount), endAmount == null ? null : new BigDecimal(endAmount),
                startDate, endDate, at(startDate, LocalTime.MIDNIGHT), null);
    }

    private static CyclePosition position(StrategyCycle cycle, LocalDate date, String value) {
        return position(cycle, date, LocalTime.NOON, value);
    }

    private static CyclePosition position(StrategyCycle cycle, LocalDate date, LocalTime time, String value) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(value),
                null, null, 0, at(date, time), null);
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KST).toInstant();
    }
}
