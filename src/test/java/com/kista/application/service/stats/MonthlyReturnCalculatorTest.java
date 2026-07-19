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
    void 일말_사이클_교체의_추가_투입금은_당일_수익을_보존한다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "150", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "150"),
                        position(next, FEBRUARY_28, "150")),
                JANUARY_1, FEBRUARY_28);

        // 장중 100 -> 110 수익 후 일말에 40을 추가 투입해 150으로 교체한다.
        assertThat(result).last().satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.monthlyReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
    }

    @Test
    void 같은_날_교체된_사이클의_전액_재투자는_중복_평가하지_않는다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "110", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "110"),
                        position(next, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.monthlyReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
    }

    @Test
    void VR_롤오버의_현금과_보유주식이_그대로_이월되면_외부흐름은_0이다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "50", "50", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "50", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "50", "10", null, 10),
                        position(previous, FEBRUARY_1, "50", "10", null, 10),
                        position(next, FEBRUARY_1, "50", "10", null, 10),
                        position(next, FEBRUARY_28, "50", "10", null, 10)),
                JANUARY_1, FEBRUARY_28);

        // 현금 50만 비교하지 않고 이전·신규 사이클의 전체 평가액 150을 비교한다.
        assertThat(result).last().satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
            assertThat(point.monthlyReturn()).isEqualTo(new BigDecimal("0.0000000000"));
        });
    }

    @Test
    void 일말_사이클_교체의_일부_회수는_당일_수익을_보존한다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "90", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "90"),
                        position(next, FEBRUARY_28, "90")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(MonthlyInvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 보유주식이_있는_신규_사이클은_첫_완전평가액_전체를_외부자금으로_처리한다() {
        StrategyCycle existing = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle added = activeCycle(UUID.randomUUID(), "100", FEBRUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(existing, added),
                List.of(position(existing, JANUARY_31, "100"),
                        position(added, FEBRUARY_1, "50", "10", null, 10),
                        position(added, FEBRUARY_28, "50", "10", null, 10)),
                JANUARY_1, FEBRUARY_28);

        // 신규 사이클의 실제 초기 자본은 startAmount 100이 아니라 50 + 10*10 = 150이다.
        assertThat(result).last().satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
            assertThat(point.monthlyReturn()).isEqualTo(new BigDecimal("0.0000000000"));
        });
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
    void 활성_사이클_모두의_첫_완전평가가_모일_때까지_포트폴리오를_내보내지_않는다() {
        StrategyCycle first = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle delayed = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(first, delayed),
                List.of(position(first, JANUARY_31, "100"),
                        position(delayed, FEBRUARY_28, "900")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point.baseMonth()).isEqualTo(FEBRUARY_1);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        });
    }

    @Test
    void 보유수량이_있는데_종가가_없으면_평단가로_대체하지_않고_그날을_생략한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "0", null, "10", 10),
                        position(cycle, FEBRUARY_28, "0", "10", "10", 10)),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point.baseMonth()).isEqualTo(FEBRUARY_1);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        });
    }

    @Test
    void KST_자정은_서로_다른_평가일로_구분한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, Instant.parse("2026-01-31T14:59:59Z"), "100"),
                        position(cycle, Instant.parse("2026-01-31T15:00:00Z"), "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result)
                .extracting(MonthlyInvestmentPoint::baseMonth, MonthlyInvestmentPoint::investmentIndexUsd)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JANUARY_1, new BigDecimal("100.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(FEBRUARY_1, new BigDecimal("110.0000000000")));
    }

    @Test
    void 최종_전략_종료는_종료일_전체평가액을_일말_회수하고_생존전략_수익을_보존한다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, FEBRUARY_1);
        StrategyCycle active = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(ended, active),
                List.of(position(ended, JANUARY_31, "0", "10", null, 10),
                        position(ended, FEBRUARY_1, "10", "10", null, 10),
                        position(active, JANUARY_31, "100"),
                        position(active, FEBRUARY_1, "110"),
                        position(active, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        // 종료 전략의 현금 endAmount 100이 아닌 마지막 전체 평가액 110을 종료일에 회수한다.
        assertThat(result).last().satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.monthlyReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
    }

    @Test
    void 개별_전략_시계열은_마지막_사이클의_종료월에서_끝난다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, JANUARY_31);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(ended), List.of(position(ended, JANUARY_31.minusDays(1), "100")),
                JANUARY_1, MARCH_31);

        assertThat(result)
                .extracting(MonthlyInvestmentPoint::baseMonth)
                .containsExactly(JANUARY_1);
    }

    @Test
    void 직전_평가액이_0_이하면_그날만_생략한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "0", JANUARY_1);

        List<MonthlyInvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "0"),
                        position(cycle, FEBRUARY_1, "50"),
                        position(cycle, FEBRUARY_28, "55")),
                JANUARY_1, FEBRUARY_28);

        // 2월 1일은 직전 평가액이 0이므로 생략하고, 현재 평가액 50은 다음 비교 기준으로 갱신한다.
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

    private static CyclePosition position(StrategyCycle cycle, LocalDate date, String usdDeposit,
                                          String closingPrice, String avgPrice, int holdings) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(usdDeposit),
                decimal(closingPrice), decimal(avgPrice), holdings, at(date, LocalTime.NOON), null);
    }

    private static CyclePosition position(StrategyCycle cycle, Instant createdAt, String value) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(value),
                null, null, 0, createdAt, null);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KST).toInstant();
    }
}
