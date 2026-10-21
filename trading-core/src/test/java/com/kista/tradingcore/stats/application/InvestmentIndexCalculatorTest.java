package com.kista.tradingcore.stats.application;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.stats.domain.model.InvestmentPoint;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentIndexCalculatorTest {

    private static final LocalDate JANUARY_1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate JANUARY_31 = LocalDate.of(2026, 1, 31);
    private static final LocalDate FEBRUARY_1 = LocalDate.of(2026, 2, 1);
    private static final LocalDate FEBRUARY_2 = LocalDate.of(2026, 2, 2);
    private static final LocalDate FEBRUARY_27 = LocalDate.of(2026, 2, 27);
    private static final LocalDate FEBRUARY_28 =LocalDate.of(2026, 2, 28);
    private static final LocalDate MARCH_31 = LocalDate.of(2026, 3, 31);

    private final InvestmentIndexCalculator calculator = new InvestmentIndexCalculator();

    @Test
    void 단일_사이클의_일별_USD_평가액으로_누적지수를_계산한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"), position(cycle, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        // 첫 완전평가일(1/31)부터 스냅샷 없는 날은 직전 평가액을 이어 매일 1포인트
        assertThat(result.getFirst().baseDate()).isEqualTo(JANUARY_31);
        assertThat(result).hasSize(29);
        assertThat(pointOn(result, FEBRUARY_27).investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 시드_비점유_사이클의_일별_순투입액은_외부_현금흐름이라_지수를_흔들지_않는다() {
        StrategyCycle dca = activeCycle(UUID.randomUUID(), "0", JANUARY_1);
        LocalDate jan2 = JANUARY_1.plusDays(1);
        LocalDate jan3 = JANUARY_1.plusDays(2);
        // 1/1 BUY 100(시작일 — 평가값에 이미 포함), 1/2 BUY 100 추가(가격 불변), 1/3 가격 10% 상승
        Map<UUID, TreeMap<LocalDate, BigDecimal>> invested = Map.of(dca.id(), new TreeMap<>(Map.of(
                JANUARY_1, new BigDecimal("100"), jan2, new BigDecimal("200"))));

        List<InvestmentPoint> result = calculator.calculate(
                List.of(dca),
                List.of(position(dca, JANUARY_1, "100"), position(dca, jan2, "200"), position(dca, jan3, "220")),
                JANUARY_1, jan3, invested);

        // 추가 매수 100은 수익이 아니다: 1/2 지수 100 유지, 1/3 +10%
        assertThat(pointOn(result, jan2).investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(pointOn(result, jan3).investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 일말_사이클_교체의_추가_투입금은_당일_수익을_보존한다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "150", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "150"),
                        position(next, FEBRUARY_28, "150")),
                JANUARY_1, FEBRUARY_28);

        // 장중 100 -> 110 수익 후 일말에 40을 추가 투입해 150으로 교체한다.
        assertThat(pointOn(result, FEBRUARY_1)).satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 같은_날_교체된_사이클의_전액_재투자는_중복_평가하지_않는다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "110", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "110"),
                        position(next, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(pointOn(result, FEBRUARY_1)).satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void VR_롤오버의_현금과_보유주식이_그대로_이월되면_외부흐름은_0이다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "50", "50", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "50", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "50", "10", null, 10),
                        position(previous, FEBRUARY_1, "50", "10", null, 10),
                        position(next, FEBRUARY_1, "50", "10", null, 10),
                        position(next, FEBRUARY_28, "50", "10", null, 10)),
                JANUARY_1, FEBRUARY_28);

        // 현금 50만 비교하지 않고 이전·신규 사이클의 전체 평가액 150을 비교한다.
        assertThat(pointOn(result, FEBRUARY_1)).satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.0000000000"));
        });
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
    }

    @Test
    void 일말_사이클_교체의_일부_회수는_당일_수익을_보존한다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "110", JANUARY_1, FEBRUARY_1);
        StrategyCycle next = activeCycle(strategyId, "90", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(previous, next),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, LocalTime.of(10, 0), "110"),
                        position(next, FEBRUARY_1, "90"),
                        position(next, FEBRUARY_28, "90")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(InvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 보유주식이_있는_신규_사이클은_첫_완전평가액_전체를_외부자금으로_처리한다() {
        StrategyCycle existing = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle added = activeCycle(UUID.randomUUID(), "100", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(existing, added),
                List.of(position(existing, JANUARY_31, "100"),
                        position(added, FEBRUARY_1, "50", "10", null, 10),
                        position(added, FEBRUARY_28, "50", "10", null, 10)),
                JANUARY_1, FEBRUARY_28);

        // 신규 사이클의 실제 초기 자본은 startAmount 100이 아니라 50 + 10*10 = 150이다.
        assertThat(pointOn(result, FEBRUARY_1)).satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.0000000000"));
        });
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
    }

    @Test
    void 여러_전략은_일별_포트폴리오로_합산한_뒤_수익률을_계산한다() {
        StrategyCycle small = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle large = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(small, large),
                List.of(position(small, JANUARY_31, "100"),
                        position(large, JANUARY_31, "900"),
                        position(small, FEBRUARY_28, "200"),
                        position(large, FEBRUARY_28, "900")),
                JANUARY_1, FEBRUARY_28);

        // 전략별 수익률 평균 50%가 아니라 합산 자산 1,000 -> 1,100의 10%다.
        assertThat(result).last().extracting(InvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 같은_날_같은_사이클은_시간상_마지막_스냅샷을_사용한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        CyclePosition earlier = position(cycle, FEBRUARY_28, LocalTime.of(10, 0), "120");
        CyclePosition later = position(cycle, FEBRUARY_28, LocalTime.of(11, 0), "110");

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"), later, earlier),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().extracting(InvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 스냅샷이_없는_날과_달은_직전_평가액을_이어_사용한다() {
        StrategyCycle first = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle second = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(first, second),
                List.of(position(first, JANUARY_31, "100"),
                        position(second, JANUARY_31, "900"),
                        position(second, FEBRUARY_28, "990")),
                JANUARY_1, MARCH_31);

        assertThat(pointOn(result, JANUARY_31).investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(pointOn(result, FEBRUARY_28).investmentIndexUsd()).isEqualTo(new BigDecimal("109.0000000000"));
        assertThat(result.getLast()).satisfies(point -> {
            assertThat(point.baseDate()).isEqualTo(MARCH_31);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("109.0000000000"));
        });
    }

    @Test
    void 활성_사이클_모두의_첫_완전평가가_모일_때까지_포트폴리오를_내보내지_않는다() {
        StrategyCycle first = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        StrategyCycle delayed = activeCycle(UUID.randomUUID(), "900", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(first, delayed),
                List.of(position(first, JANUARY_31, "100"),
                        position(delayed, FEBRUARY_28, "900")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point.baseDate()).isEqualTo(FEBRUARY_28);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        });
    }

    @Test
    void 보유수량이_있는데_종가가_없으면_평단가로_대체하지_않고_그날을_생략한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "0", null, "10", 10),
                        position(cycle, FEBRUARY_28, "0", "10", "10", 10)),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).singleElement().satisfies(point -> {
            assertThat(point.baseDate()).isEqualTo(FEBRUARY_28);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("100.0000000000"));
        });
    }

    @Test
    void KST_자정은_서로_다른_평가일로_구분한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, Instant.parse("2026-01-31T14:59:59Z"), "100"),
                        position(cycle, Instant.parse("2026-01-31T15:00:00Z"), "110")),
                JANUARY_1, FEBRUARY_1);

        assertThat(result)
                .extracting(InvestmentPoint::baseDate, InvestmentPoint::investmentIndexUsd)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JANUARY_31, new BigDecimal("100.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(FEBRUARY_1, new BigDecimal("110.0000000000")));
    }

    @Test
    void 최종_전략_종료는_종료일_전체평가액을_일말_회수하고_생존전략_수익을_보존한다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, FEBRUARY_1);
        StrategyCycle active = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(ended, active),
                List.of(position(ended, JANUARY_31, "0", "10", null, 10),
                        position(ended, FEBRUARY_1, "10", "10", null, 10),
                        position(active, JANUARY_31, "100"),
                        position(active, FEBRUARY_1, "110"),
                        position(active, FEBRUARY_28, "110")),
                JANUARY_1, FEBRUARY_28);

        // 종료 전략의 현금 endAmount 100이 아닌 마지막 전체 평가액 110을 종료일에 회수한다.
        assertThat(pointOn(result, FEBRUARY_1)).satisfies(point -> {
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
        assertThat(result.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 단독_전략의_최종_종료일은_0인_교체후_평가액으로_당일_수익을_보존한다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(ended),
                List.of(position(ended, JANUARY_31, "100"),
                        position(ended, FEBRUARY_1, "110")),
                JANUARY_1, FEBRUARY_28);

        assertThat(result).last().satisfies(point -> {
            assertThat(point.baseDate()).isEqualTo(FEBRUARY_1);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("110.0000000000"));
            assertThat(point.periodReturn()).isEqualTo(new BigDecimal("0.1000000000"));
        });
    }

    @Test
    void 같은_전략의_사이클_사이에_날짜_공백이_있으면_종료와_재진입을_각각_외부흐름으로_처리한다() {
        UUID strategyId = UUID.randomUUID();
        StrategyCycle previous = closedCycle(strategyId, "100", "100", JANUARY_1, FEBRUARY_1);
        StrategyCycle restarted = activeCycle(strategyId, "150", LocalDate.of(2026, 3, 10));
        StrategyCycle survivor = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(previous, restarted, survivor),
                List.of(position(previous, JANUARY_31, "100"),
                        position(previous, FEBRUARY_1, "100"),
                        position(survivor, JANUARY_31, "100"),
                        position(survivor, FEBRUARY_28, "100"),
                        position(restarted, LocalDate.of(2026, 3, 10), "150"),
                        position(restarted, MARCH_31, "150")),
                JANUARY_1, MARCH_31);

        // 종료일(2/1) 회수와 재진입일(3/10) 투입이 각각 외부흐름이라 지수는 전 구간 100 유지
        assertThat(result)
                .extracting(InvestmentPoint::investmentIndexUsd)
                .containsOnly(new BigDecimal("100.0000000000"));
        assertThat(result.getFirst().baseDate()).isEqualTo(JANUARY_31);
        assertThat(result.getLast().baseDate()).isEqualTo(MARCH_31);
    }

    @Test
    void 개별_전략_시계열은_마지막_사이클의_종료일에서_끝난다() {
        StrategyCycle ended = closedCycle(UUID.randomUUID(), "100", "100", JANUARY_1, JANUARY_31);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(ended), List.of(position(ended, JANUARY_31.minusDays(1), "100")),
                JANUARY_1, MARCH_31);

        assertThat(result)
                .extracting(InvestmentPoint::baseDate)
                .containsExactly(JANUARY_31.minusDays(1), JANUARY_31);
    }

    @Test
    void 직전_평가액이_0_이하면_그날만_생략한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "0", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "0"),
                        position(cycle, FEBRUARY_1, "50"),
                        position(cycle, FEBRUARY_28, "55")),
                JANUARY_1, FEBRUARY_28);

        // 2월 1일은 직전 평가액이 0이므로 생략하고, 현재 평가액 50은 다음 비교 기준으로 갱신한다.
        assertThat(result).last().extracting(InvestmentPoint::investmentIndexUsd)
                .isEqualTo(new BigDecimal("110.0000000000"));
    }

    @Test
    void 스냅샷_사이_날짜는_직전_누적지수를_유지한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, LocalDate.of(2026, 1, 10), "100"),
                        position(cycle, LocalDate.of(2026, 1, 20), "120")),
                JANUARY_1, JANUARY_31);

        assertThat(pointOn(result, LocalDate.of(2026, 1, 19)).investmentIndexUsd())
                .isEqualTo(new BigDecimal("100.0000000000"));
        assertThat(result.getLast()).satisfies(point -> {
            assertThat(point.baseDate()).isEqualTo(JANUARY_31);
            assertThat(point.investmentIndexUsd()).isEqualTo(new BigDecimal("120.0000000000"));
        });
    }

    @Test
    void 월중_저점도_일별_포인트로_방출한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);
        List<CyclePosition> positions = new ArrayList<>(List.of(
                position(cycle, JANUARY_1, "100"),
                position(cycle, LocalDate.of(2026, 1, 15), "50"),
                position(cycle, JANUARY_31, "100"),
                position(cycle, FEBRUARY_28, "80"),
                position(cycle, MARCH_31, "120")));

        List<InvestmentPoint> points = calculator.calculate(
                List.of(cycle), positions, JANUARY_1, MARCH_31);

        // MDD 자체는 BenchmarkComparisonBuilder가 계산 — 여기서는 월중 저점이 시리즈에 포함되는지 확인한다
        assertThat(pointOn(points, LocalDate.of(2026, 1, 15)).investmentIndexUsd())
                .isEqualTo(new BigDecimal("50.0000000000"));
        assertThat(pointOn(points, FEBRUARY_28).investmentIndexUsd()).isEqualTo(new BigDecimal("80.0000000000"));
        assertThat(points.getLast().investmentIndexUsd()).isEqualTo(new BigDecimal("120.0000000000"));
    }

    @Test
    void 매_유효_평가일마다_포인트를_방출한다() {
        StrategyCycle cycle = activeCycle(UUID.randomUUID(), "100", JANUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(cycle),
                List.of(position(cycle, JANUARY_31, "100"),
                        position(cycle, FEBRUARY_1, "110"),
                        position(cycle, FEBRUARY_2, "121")),
                JANUARY_31, FEBRUARY_2);

        assertThat(result)
                .extracting(InvestmentPoint::baseDate, InvestmentPoint::investmentIndexUsd, InvestmentPoint::periodReturn)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                JANUARY_31, new BigDecimal("100.0000000000"), new BigDecimal("0.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(
                                FEBRUARY_1, new BigDecimal("110.0000000000"), new BigDecimal("0.1000000000")),
                        org.assertj.core.groups.Tuple.tuple(
                                FEBRUARY_2, new BigDecimal("121.0000000000"), new BigDecimal("0.1000000000")));
    }

    @Test
    void 시작예정일_전략은_등록_스냅샷_평가액으로_진입해_가짜_손익을_만들지_않는다() {
        StrategyCycle existing = activeCycle(UUID.randomUUID(), "1000", JANUARY_1);
        // 1/30 등록·2/1 시작 예정 — 시작일 당일 스냅샷 없이 다음 날 첫 배치 스냅샷이 생긴다
        StrategyCycle scheduled = activeCycle(UUID.randomUUID(), "1000", FEBRUARY_1);

        List<InvestmentPoint> result = calculator.calculate(
                List.of(existing, scheduled),
                List.of(position(existing, JANUARY_31, "1000"),
                        position(existing, FEBRUARY_1, "1000"),
                        position(existing, FEBRUARY_2, "1000"),
                        position(scheduled, LocalDate.of(2026, 1, 30), "1000"),
                        position(scheduled, FEBRUARY_2, "1100")),
                JANUARY_31, FEBRUARY_2);

        // 2/1은 신규 진입만 있어 0%, 2/2는 합산 2,000 -> 2,100의 +5%
        assertThat(result)
                .extracting(InvestmentPoint::baseDate, InvestmentPoint::investmentIndexUsd)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JANUARY_31, new BigDecimal("100.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(FEBRUARY_1, new BigDecimal("100.0000000000")),
                        org.assertj.core.groups.Tuple.tuple(FEBRUARY_2, new BigDecimal("105.0000000000")));
    }

    private static InvestmentPoint pointOn(List<InvestmentPoint> points, LocalDate date) {
        return points.stream().filter(point -> point.baseDate().equals(date)).findFirst().orElseThrow();
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
                null, null, BigDecimal.valueOf(0), at(date, time), null);
    }

    private static CyclePosition position(StrategyCycle cycle, LocalDate date, String usdDeposit,
                                          String closingPrice, String avgPrice, int holdings) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(usdDeposit),
                decimal(closingPrice), decimal(avgPrice), BigDecimal.valueOf(holdings), at(date, LocalTime.NOON), null);
    }

    private static CyclePosition position(StrategyCycle cycle, Instant createdAt, String value) {
        return new CyclePosition(UUID.randomUUID(), cycle.id(), new BigDecimal(value),
                null, null, BigDecimal.valueOf(0), createdAt, null);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KST).toInstant();
    }
}
