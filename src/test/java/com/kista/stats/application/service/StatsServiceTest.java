package com.kista.stats.application.service;

import com.kista.sharedkernel.TimeZones;
import com.kista.stats.domain.model.*;
import com.kista.stats.application.port.output.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// Task 5 이후: StatsService는 accountPort/strategyPort/strategyCyclePort/cyclePositionPort를 직접
// 호출하지 않고 investmentPointsPort(HTTP 경유)로부터 InvestmentPoint 시리즈를 받는다. 소유권 검증·
// MOCK 계좌 필터링·MonthlyReturnCalculator 배선 등은 trading-core의 InvestmentPointsQueryService로
// 이전됐고 그쪽 테스트(InvestmentPointsQueryServiceTest)가 커버한다 — 여기서는 StatsService 자신의
// 책임(KB 조사일 as-of 스냅, ETF 거래일 보정, 캐시∥환율 병렬 조회, 검증 fast-fail)만 검증한다.
@ExtendWith(MockitoExtension.class)
class StatsServiceTest {

    @Mock InvestmentPointsPort investmentPointsPort;
    @Mock HousingBenchmarkPricePort housingBenchmarkPricePort;
    @Mock HousingPriceIndexPort housingPriceIndexPort;
    @Mock CurrentExchangeRatePort currentExchangeRatePort;
    @Mock IndexPricePort indexPricePort;
    // getOrCompute가 null을 반환하지 않도록 mock이 아닌 실제 캐시 인스턴스 사용 (@InjectMocks가 생성자로 주입)
    @Spy StatsResultCache statsResultCache = new StatsResultCache();
    @InjectMocks StatsService statsService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STRATEGY_ID = UUID.randomUUID();
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 2, 28);

    private static final StrategyRef STRATEGY = new StrategyRef(
            STRATEGY_ID, StrategyType.INFINITE, StrategyTicker.SOXL);

    private static InvestmentPoint point(String date, String index) {
        return new InvestmentPoint(LocalDate.parse(date), new BigDecimal(index), null);
    }

    private void stubPortfolioWeekly(List<InvestmentPoint> points, LocalDate from, LocalDate to) {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.PORTFOLIO), isNull(),
                any(), any(), eq(BenchmarkGranularity.WEEKLY)))
                .thenReturn(new InvestmentPointsPort.Result(points, from, to, null));
    }

    private void stubStrategyWeekly(List<InvestmentPoint> points, LocalDate from, LocalDate to) {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.WEEKLY)))
                .thenReturn(new InvestmentPointsPort.Result(points, from, to, STRATEGY));
    }

    // 첫 KB 조사일(01-05)~마지막(02-23)까지 이어지는 단순 포트폴리오 투자 시리즈 + KB 지수 스텁
    private void stubWeeklyPortfolioComparison(String startValue, String endValue) {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", startValue),
                point("2026-02-23", endValue)), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());
    }

    private static HousingBenchmarkPrice benchmarkPrice(
            LocalDate month, String first, String second, String third, String fourth, String fifth) {
        return new HousingBenchmarkPrice(
                HousingBenchmarkPrice.SOURCE_KBLAND,
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE,
                "1100000000", "서울", month,
                new BigDecimal(first), new BigDecimal(second), new BigDecimal(third),
                new BigDecimal(fourth), new BigDecimal(fifth), new BigDecimal("6.5"),
                LocalDate.of(2026, 2, 15), java.time.Instant.parse("2026-02-16T00:00:00Z"));
    }

    private static List<HousingBenchmarkPrice> benchmarkPrices() {
        return List.of(
                benchmarkPrice(FROM, "10", "20", "40", "80", "160"),
                benchmarkPrice(LocalDate.of(2026, 2, 1), "20", "60", "160", "400", "960"));
    }

    private static HousingPriceIndex weeklyIndex(LocalDate baseDate, String value) {
        return new HousingPriceIndex(
                HousingPriceIndex.SOURCE_KBLAND, HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                "1100000000", "서울", baseDate, new BigDecimal(value),
                LocalDate.of(2026, 2, 15), java.time.Instant.parse("2026-02-16T00:00:00Z"));
    }

    private static List<HousingPriceIndex> weeklyIndices() {
        return List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "400"));
    }

    @Test
    void 포트폴리오와_아파트_지수를_첫_KB_조사일_100으로_비교한다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-02-23", "184.20")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "400")));
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.scope()).isEqualTo(BenchmarkScope.PORTFOLIO);
        assertThat(result.strategy()).isNull();
        assertThat(result.benchmark().regionCode()).isEqualTo("1100000000");
        assertThat(result.benchmark().regionName()).isEqualTo("서울");
        assertThat(result.benchmark().label()).isEqualTo("서울 아파트 매매가격지수");
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().getFirst().investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(result.points().getFirst().benchmarkIndex()).isEqualByComparingTo("100.0");
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("184.2");
        assertThat(result.points().getLast().benchmarkIndex()).isEqualByComparingTo("400.0");
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.842");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("3.0");
        assertThat(result.summary().excessReturn()).isEqualByComparingTo("-2.158");
        // 49일 구간(< 90일)이라 연환산 수익률은 억제된다
        assertThat(result.summary().investmentAnnualizedReturn()).isNull();
        assertThat(result.summary().benchmarkAnnualizedReturn()).isNull();
        assertThat(result.currentExchangeRate().midRate()).isEqualByComparingTo("1365.20");
        assertThat(result.currentExchangeRate().source()).isEqualTo("TOSS_INVEST");
        assertThat(result.currentExchangeRate().fetchedAt()).isNotNull();
        assertThat(result.emptyReason()).isNull();

        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                "1100000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        verify(currentExchangeRatePort, times(1)).getMidRate();
    }

    @Test
    void 구간이_90일_이상이면_연환산_수익률을_계산한다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-05-04", "184.20")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 5, 4));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 5, 4), "400")));
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 5, 4));

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
        assertThat(result.summary().benchmarkAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(4.0, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }

    @Test
    void KB_결측_주가_있어도_구간_수익률이_비지_않는다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-02-16", "110.00")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 16));
        // 2026-01-12 조사 주가 KB 결측으로 비어 있어 1/5 -> 2/16으로 3주 이상 건너뛴다
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 16), "121")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 16));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 16));
        // 옛 MONTHLY 로직이었다면 정확히 1개월 뒤가 아니라는 이유로 null이 됐을 구간이지만,
        // WEEKLY는 결측 주와 무관하게 인접 공통 포인트끼리 항상 수익률을 계산한다
        assertThat(result.points().getLast().investmentPeriodReturn()).isEqualByComparingTo("0.1");
        assertThat(result.points().getLast().benchmarkPeriodReturn()).isEqualByComparingTo("0.21");
        assertThat(result.summary().investmentCumulativeReturn()).isEqualByComparingTo("0.1");
        assertThat(result.summary().benchmarkCumulativeReturn()).isEqualByComparingTo("0.21");
    }

    @Test
    void 조사일과_정확히_일치하지_않는_투자_스냅샷은_직전_값으로_carry_back된다() {
        // 투자 시리즈는 01-09(100)와 01-13(110) 두 시점만 있다 — 01-12는 트레이딩 측
        // MonthlyReturnCalculator가 평가 불가능(closingPrice 없음)으로 판단해 시리즈에서 빠진
        // 상태를 그대로 반영한다. KB 조사일 01-12는 investmentByDate에 정확히 일치하는 키가
        // 없어 floorEntry의 진짜 carry-back 경로(직전 01-09의 값)를 타게 된다.
        stubPortfolioWeekly(List.of(
                point("2026-01-09", "100.00"),
                point("2026-01-13", "110.00")), LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 13));
        // KB 조사일은 01-09/01-12/01-13 — 01-12는 두 투자 스냅샷 사이에 끼어 정확히 일치하지 않는다
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 9), "100"),
                weeklyIndex(LocalDate.of(2026, 1, 12), "105"),
                weeklyIndex(LocalDate.of(2026, 1, 13), "110")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 13));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 13));
        // 01-12는 아직 01-13 스냅샷(110)이 오지 않았으므로 직전 01-09 값(100)을 그대로 이어받는다
        assertThat(result.points().get(1).investmentIndexUsd()).isEqualByComparingTo("100.0");
        // 01-13에서야 비로소 값이 갱신된다
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("110.0");
    }

    @Test
    void 투자_종료_이후_조사일은_지수_고정_없이_스킵된다() {
        // 투자가 01-13에 종료되고 이후 재등록이 없다 — 01-20/01-27 조사일은
        // investmentByDate의 마지막 키(01-13)보다 뒤라 floorEntry가 01-13 값을
        // 그대로 반환해버리면 투자지수가 고정된 채 벤치마크만 계속 움직이는
        // 착시가 생긴다. 가드가 없으면 이 두 조사일도 결과에 포함된다.
        stubPortfolioWeekly(List.of(
                point("2026-01-09", "100.00"),
                point("2026-01-13", "110.00")), LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 27));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 9), "100"),
                weeklyIndex(LocalDate.of(2026, 1, 13), "110"),
                weeklyIndex(LocalDate.of(2026, 1, 20), "130"),
                weeklyIndex(LocalDate.of(2026, 1, 27), "150")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 27));

        // 투자 종료(01-13) 이후 조사일(01-20, 01-27)은 지수 고정 없이 스킵된다
        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 1, 9), LocalDate.of(2026, 1, 13));
        assertThat(result.period().toDate()).isEqualTo(LocalDate.of(2026, 1, 13));
    }

    @Test
    void 아파트_벤치마크는_지정된_regionCode의_지수를_조회하고_지역명을_동적으로_채운다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-02-23", "184.20")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                eq(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX), eq("2600000000"), any(), any()))
                .thenReturn(List.of(
                        new HousingPriceIndex(HousingPriceIndex.SOURCE_KBLAND,
                                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                                "2600000000", "부산", LocalDate.of(2026, 1, 5), new BigDecimal("100"),
                                null, java.time.Instant.parse("2026-02-16T00:00:00Z")),
                        new HousingPriceIndex(HousingPriceIndex.SOURCE_KBLAND,
                                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX,
                                "2600000000", "부산", LocalDate.of(2026, 2, 23), new BigDecimal("110"),
                                null, java.time.Instant.parse("2026-02-16T00:00:00Z"))));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "2600000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.benchmark().regionCode()).isEqualTo("2600000000");
        assertThat(result.benchmark().regionName()).isEqualTo("부산");
        assertThat(result.benchmark().label()).isEqualTo("부산 아파트 매매가격지수");
        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "2600000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
    }

    @Test
    void 투자_시작_전_KB_조사일은_스킵하고_이후_조사일부터_비교한다() {
        stubPortfolioWeekly(List.of(
                point("2026-02-02", "100.00"),
                point("2026-02-23", "110.00")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        // 1/5 조사일은 투자 시작(2/2) 이전이라 as-of 값이 없어 스킵된다
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "90"),
                weeklyIndex(LocalDate.of(2026, 2, 2), "100"),
                weeklyIndex(LocalDate.of(2026, 2, 23), "121")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 2, 23));
    }

    @Test
    void 주간_지수의_고점_대비_최대낙폭을_계산한다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-01-19", "80.00"),
                point("2026-02-02", "120.00")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 2));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of(
                weeklyIndex(LocalDate.of(2026, 1, 5), "100"),
                weeklyIndex(LocalDate.of(2026, 1, 19), "90"),
                weeklyIndex(LocalDate.of(2026, 2, 2), "135")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 2));

        assertThat(result.summary().investmentMaxDrawdown()).isEqualByComparingTo("-0.2");
        assertThat(result.summary().benchmarkMaxDrawdown()).isEqualByComparingTo("-0.1");
    }

    @Test
    void 소유한_개별_전략만_조회하고_전략_메타데이터를_반환한다() {
        stubStrategyWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-02-23", "110.00")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.strategy().id()).isEqualTo(STRATEGY_ID);
        assertThat(result.strategy().type()).isEqualTo(StrategyType.INFINITE);
        assertThat(result.strategy().ticker()).isEqualTo(StrategyTicker.SOXL);
        verify(investmentPointsPort).fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.WEEKLY));
    }

    // Task 5 boundary 이전에는 authorizeIfStrategyScope가 병렬 실행(본체∥환율) 전에 소유권을
    // 동기적으로 검증해 인가 실패 시 환율 API 호출 자체를 막았다. 이제 소유권 검증은
    // investmentPointsPort.fetch() 내부(trading 쪽 HTTP 호출)로 이동해, 병렬 실행이 이미 시작된
    // 뒤에야 실패가 드러난다 — invokeAll()이 두 잡을 모두 실행 완료할 때까지 기다리므로 인가 실패여도
    // 환율 조회가 이미 낭비 호출된다. fast-fail 회귀 확인용 테스트.
    @Test
    void 소유하지_않은_전략은_예외를_전파하되_환율_병렬_호출은_낭비된다() {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.WEEKLY)))
                .thenThrow(new SecurityException("소유하지 않은 계좌입니다"));
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, "1100000000", FROM, TO))
                .isInstanceOf(SecurityException.class);

        verify(currentExchangeRatePort, times(1)).getMidRate();
    }

    @Test
    void 역전된_기간은_데이터를_읽기_전에_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", TO, FROM))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(investmentPointsPort, housingPriceIndexPort, currentExchangeRatePort);
    }

    @Test
    void 투자_데이터가_없으면_NO_INVESTMENT_DATA를_반환한다() {
        stubPortfolioWeekly(List.of(), FROM, TO);
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(weeklyIndices());

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000", FROM, TO);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("NO_INVESTMENT_DATA");
        verify(currentExchangeRatePort).getMidRate();
    }

    @Test
    void 공통_조사일이_두_개_미만이면_INSUFFICIENT_COMMON_MONTHS를_반환한다() {
        stubWeeklyPortfolioComparison("100.00", "110.00");
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any()))
                .thenReturn(List.of(weeklyIndex(LocalDate.of(2026, 1, 5), "100")));

        HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("INSUFFICIENT_COMMON_MONTHS");
    }

    @Test
    void 환율_예외는_완성된_비교_결과에서_환율만_null로_격리한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(currentExchangeRatePort.getMidRate())
                .thenReturn(new BigDecimal("1365.20"))
                .thenThrow(new IllegalStateException("환율 조회 실패"));

        HousingBenchmarkComparison success = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        HousingBenchmarkComparison isolated = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(isolated.currentExchangeRate()).isNull();
        assertThat(isolated.points()).isEqualTo(success.points());
        assertThat(isolated.period()).isEqualTo(success.period());
        assertThat(isolated.summary()).isEqualTo(success.summary());
        assertThat(isolated.benchmark()).isEqualTo(success.benchmark());
        assertThat(isolated.emptyReason()).isEqualTo(success.emptyReason());
        verify(currentExchangeRatePort, times(2)).getMidRate();
    }

    @Test
    void 시계열_조회는_from_to를_그대로_port에_전달한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO))
                .thenReturn(benchmarkPrices());

        List<HousingBenchmarkPrice> result = statsService.getHousingBenchmarkSeries(FROM, TO, null);

        assertThat(result).isEqualTo(benchmarkPrices());
        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO);
    }

    @Test
    void 시계열_조회는_from_to가_모두_없으면_최소날짜부터_오늘까지_조회한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);

        statsService.getHousingBenchmarkSeries(null, null, null);

        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                eq(HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE), eq("1100000000"),
                fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    void 시계열_조회는_역전된_기간을_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingBenchmarkSeries(TO, FROM, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(housingBenchmarkPricePort);
    }

    @Test
    void 시계열_조회는_지정된_regionCode를_그대로_port에_전달한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "2600000000", FROM, TO))
                .thenReturn(List.of());

        statsService.getHousingBenchmarkSeries(FROM, TO, "2600000000");

        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "2600000000", FROM, TO);
    }

    @Test
    void 시계열_조회는_공백_regionCode를_서울로_대체한다() {
        when(housingBenchmarkPricePort.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO))
                .thenReturn(List.of());

        statsService.getHousingBenchmarkSeries(FROM, TO, "  ");

        verify(housingBenchmarkPricePort).findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                HousingBenchmarkPrice.METRIC_APT_QTE_SALE_PRICE, "1100000000", FROM, TO);
    }

    @Test
    void 매매가격지수_시계열_조회는_from_to를_그대로_port에_전달한다() {
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "1100000000", FROM, TO))
                .thenReturn(weeklyIndices());

        List<HousingPriceIndex> result = statsService.getHousingPriceIndexSeries(FROM, TO, null);

        assertThat(result).isEqualTo(weeklyIndices());
        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "1100000000", FROM, TO);
    }

    @Test
    void 매매가격지수_시계열_조회는_from_to가_모두_없으면_최소날짜부터_오늘까지_조회한다() {
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);

        statsService.getHousingPriceIndexSeries(null, null, null);

        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                eq(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX), eq("1100000000"),
                fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    void 매매가격지수_시계열_조회는_역전된_기간을_거부한다() {
        assertThatThrownBy(() -> statsService.getHousingPriceIndexSeries(TO, FROM, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(housingPriceIndexPort);
    }

    @Test
    void 매매가격지수_시계열_조회는_지정된_regionCode를_그대로_port에_전달한다() {
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "2600000000", FROM, TO))
                .thenReturn(List.of());

        statsService.getHousingPriceIndexSeries(FROM, TO, "2600000000");

        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "2600000000", FROM, TO);
    }

    @Test
    void 매매가격지수_시계열_조회는_공백_regionCode를_서울로_대체한다() {
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "1100000000", FROM, TO))
                .thenReturn(List.of());

        statsService.getHousingPriceIndexSeries(FROM, TO, "  ");

        verify(housingPriceIndexPort).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX, "1100000000", FROM, TO);
    }

    @Test
    void 지역_카탈로그는_주간_지수_port_결과를_그대로_반환한다() {
        List<HousingBenchmarkRegion> regions = List.of(
                new HousingBenchmarkRegion("1100000000", "서울"),
                new HousingBenchmarkRegion("2600000000", "부산"));
        when(housingPriceIndexPort.findDistinctRegions(HousingPriceIndex.METRIC_WEEKLY_APT_SALE_PRICE_INDEX))
                .thenReturn(regions);

        List<HousingBenchmarkRegion> result = statsService.getHousingBenchmarkRegions();

        assertThat(result).isEqualTo(regions);
    }

    @Test
    void ETF_가격_시계열_조회는_symbol_from_to를_그대로_port에_전달한다() {
        when(indexPricePort.findBySymbolAndRange("SPY", FROM, TO)).thenReturn(spyPrices());

        List<IndexPrice> result = statsService.getEtfPriceSeries(FROM, TO, EtfBenchmarkSymbol.SPY);

        assertThat(result).isEqualTo(spyPrices());
        verify(indexPricePort).findBySymbolAndRange("SPY", FROM, TO);
    }

    @Test
    void ETF_가격_시계열_조회는_from_to가_모두_없으면_최소날짜부터_오늘까지_조회한다() {
        when(indexPricePort.findBySymbolAndRange(anyString(), any(), any())).thenReturn(List.of());
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);

        statsService.getEtfPriceSeries(null, null, EtfBenchmarkSymbol.QQQ);

        verify(indexPricePort).findBySymbolAndRange(eq("QQQ"), fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(LocalDate.of(2000, 1, 1));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDate.now(TimeZones.KST));
    }

    @Test
    void ETF_가격_시계열_조회는_역전된_기간을_거부한다() {
        assertThatThrownBy(() -> statsService.getEtfPriceSeries(TO, FROM, EtfBenchmarkSymbol.SPY))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(indexPricePort);
    }

    // ── ETF 벤치마크 비교 ────────────────────────────────────────────────────

    private static List<IndexPrice> spyPrices() {
        return List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 30), new BigDecimal("400.00")),
                new IndexPrice("SPY", LocalDate.of(2026, 2, 27), new BigDecimal("440.00")));
    }

    @Test
    void ETF_벤치마크_비교는_다운샘플링_없이_거래일별_교집합으로_비교한다() {
        // IndexPrice.tradeDate는 US 거래일 원본이라 StatsService가 KST로 +1일 보정한다.
        // 투자 시리즈는 그 보정된 KST 날짜(1/6, 1/7, 2/28)에 맞춰 배치한다.
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.PORTFOLIO), isNull(),
                any(), any(), eq(BenchmarkGranularity.DAILY)))
                .thenReturn(new InvestmentPointsPort.Result(List.of(
                        point("2026-01-06", "100.00"),
                        point("2026-01-07", "102.00"),
                        point("2026-02-28", "184.20")), FROM, TO, null));
        List<IndexPrice> prices = List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 5), new BigDecimal("400.00")),
                new IndexPrice("SPY", LocalDate.of(2026, 1, 6), new BigDecimal("404.00")),
                new IndexPrice("SPY", LocalDate.of(2026, 2, 27), new BigDecimal("440.00")));
        when(indexPricePort.findBySymbolAndRange(eq("SPY"), any(), any())).thenReturn(prices);
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        HousingBenchmarkComparison result = statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, EtfBenchmarkSymbol.SPY, FROM, TO);

        assertThat(result.benchmark().assetType()).isEqualTo(BenchmarkAssetType.ETF);
        assertThat(result.benchmark().symbol()).isEqualTo("SPY");
        assertThat(result.benchmark().regionCode()).isNull();
        assertThat(result.benchmark().regionName()).isNull();
        assertThat(result.benchmark().label()).isEqualTo("SPY (SPDR S&P 500 ETF Trust)");
        // US 거래일 원본 2/27 + 1일 보정 = KST 2/28
        assertThat(result.benchmark().sourceUpdatedDate()).isEqualTo(LocalDate.of(2026, 2, 28));
        // 다운샘플링 없이 IndexPrice가 존재하는 3개 거래일이 +1일 보정된 KST 날짜로 그대로 포인트가 생긴다.
        assertThat(result.points()).extracting(HousingBenchmarkPoint::baseDate)
                .containsExactly(LocalDate.of(2026, 1, 6), LocalDate.of(2026, 1, 7), LocalDate.of(2026, 2, 28));
        assertThat(result.points().get(0).investmentIndexUsd()).isEqualByComparingTo("100.0");
        assertThat(result.points().get(0).benchmarkIndex()).isEqualByComparingTo("100.0");
        assertThat(result.points().get(1).investmentIndexUsd()).isEqualByComparingTo("102.0");
        assertThat(result.points().get(1).benchmarkIndex()).isEqualByComparingTo(
                new BigDecimal("404.00").divide(new BigDecimal("400.00"), 10, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")));
        // 캘린더 인접 여부와 무관하게(1/7 -> 2/28) 항상 직전 공통일 대비 수익률을 계산한다.
        assertThat(result.points().get(1).investmentPeriodReturn()).isEqualByComparingTo("0.02");
        assertThat(result.points().getLast().investmentIndexUsd()).isEqualByComparingTo("184.2");
        assertThat(result.points().getLast().benchmarkIndex()).isEqualByComparingTo(
                new BigDecimal("440.00").divide(new BigDecimal("400.00"), 10, java.math.RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")));
        assertThat(result.emptyReason()).isNull();
        assertThat(result.currentExchangeRate().midRate()).isEqualByComparingTo("1365.20");

        verify(indexPricePort).findBySymbolAndRange(
                "SPY", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 2, 28));
    }

    @Test
    void ETF_비교는_이번_달이_아직_끝나지_않아도_아파트처럼_지난달로_clamp하지_않는다() {
        LocalDate today = LocalDate.now(TimeZones.KST);
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.PORTFOLIO), isNull(),
                any(), any(), eq(BenchmarkGranularity.DAILY)))
                .thenReturn(new InvestmentPointsPort.Result(
                        List.of(point(today.toString(), "100.00")), today, today, null));
        when(indexPricePort.findBySymbolAndRange(eq("SPY"), any(), any())).thenReturn(List.of());
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, EtfBenchmarkSymbol.SPY, null, null);

        // 아파트(MONTHLY)는 이번 달이 미완료면 지난달 말까지만 조회하지만, ETF(DAILY)는 이번 달 말일까지
        // 그대로 조회 범위에 포함한다 — 매일 갱신되는 데이터라 발행 지연을 기다릴 필요가 없다.
        LocalDate endOfCurrentMonth = YearMonth.from(today).atEndOfMonth();
        verify(indexPricePort).findBySymbolAndRange(eq("SPY"), any(), eq(endOfCurrentMonth));
    }

    @Test
    void ETF_가격_데이터가_없으면_INSUFFICIENT_COMMON_MONTHS를_반환한다() {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.PORTFOLIO), isNull(),
                any(), any(), eq(BenchmarkGranularity.DAILY)))
                .thenReturn(new InvestmentPointsPort.Result(List.of(
                        point("2026-01-31", "100.00"),
                        point("2026-02-28", "184.20")), FROM, TO, null));
        when(indexPricePort.findBySymbolAndRange(eq("SPY"), any(), any())).thenReturn(List.of());

        HousingBenchmarkComparison result = statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, EtfBenchmarkSymbol.SPY, FROM, TO);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary()).isNull();
        assertThat(result.emptyReason()).isEqualTo("INSUFFICIENT_COMMON_MONTHS");
    }

    @Test
    void ETF_비교도_소유한_개별_전략만_조회하고_전략_메타데이터를_반환한다() {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.DAILY)))
                .thenReturn(new InvestmentPointsPort.Result(List.of(
                        point("2026-01-31", "100.00"),
                        point("2026-02-28", "110.00")), FROM, TO, STRATEGY));
        when(indexPricePort.findBySymbolAndRange(eq("QQQ"), any(), any())).thenReturn(spyPrices());

        HousingBenchmarkComparison result = statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, EtfBenchmarkSymbol.QQQ, FROM, TO);

        assertThat(result.strategy().id()).isEqualTo(STRATEGY_ID);
        verify(investmentPointsPort).fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.DAILY));
    }

    @Test
    void ETF_비교도_소유하지_않은_전략은_예외를_그대로_전파한다() {
        when(investmentPointsPort.fetch(eq(USER_ID), eq(BenchmarkScope.STRATEGY), eq(STRATEGY_ID),
                any(), any(), eq(BenchmarkGranularity.DAILY)))
                .thenThrow(new SecurityException("소유하지 않은 계좌입니다"));

        assertThatThrownBy(() -> statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, STRATEGY_ID, EtfBenchmarkSymbol.SPY, FROM, TO))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(indexPricePort);
    }

    @Test
    void ETF_비교도_전략_scope에는_strategyId가_필요하다() {
        assertThatThrownBy(() -> statsService.getEtfBenchmarkComparison(
                USER_ID, BenchmarkScope.STRATEGY, null, EtfBenchmarkSymbol.SPY, FROM, TO))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(investmentPointsPort, housingBenchmarkPricePort, currentExchangeRatePort, indexPricePort);
    }

    @Test
    void 누락되거나_0_이하인_현재_환율은_null로_격리한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                null,
                BigDecimal.ZERO,
                new BigDecimal("-1"));

        for (int invocation = 0; invocation < 3; invocation++) {
            HousingBenchmarkComparison result = statsService.getHousingBenchmarkComparison(
                    USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
            assertThat(result.currentExchangeRate()).isNull();
            assertThat(result.points()).isNotEmpty();
        }

        verify(currentExchangeRatePort, times(3)).getMidRate();
    }

    // ── 벤치마크 비교 캐시(TTL 10분) + 환율 병렬 조회 ───────────────────────────

    @Test
    void 벤치마크_비교는_같은_파라미터_재조회시_본체는_캐시하고_환율은_매번_재조회한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        HousingBenchmarkComparison first = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        HousingBenchmarkComparison second = statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        assertThat(second.points()).isEqualTo(first.points());
        // 본체(HTTP+DB) 조회는 캐시 hit이라 1회만 — investmentPointsPort/housingPriceIndexPort 둘 다 검증
        verify(investmentPointsPort, times(1)).fetch(any(), any(), any(), any(), any(), any());
        verify(housingPriceIndexPort, times(1)).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any());
        // 환율은 캐시 대상이 아니라 응답마다 후결합 재조회
        verify(currentExchangeRatePort, times(2)).getMidRate();
    }

    @Test
    void 벤치마크_비교는_regionCode가_다르면_별도_캐시_키로_본체를_재계산한다() {
        stubWeeklyPortfolioComparison("100.00", "184.20");
        when(currentExchangeRatePort.getMidRate()).thenReturn(
                new BigDecimal("1365.20"));

        statsService.getHousingBenchmarkComparison(USER_ID, BenchmarkScope.PORTFOLIO, null,
                "1100000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        statsService.getHousingBenchmarkComparison(USER_ID, BenchmarkScope.PORTFOLIO, null,
                "2600000000", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));

        // regionCode가 캐시 키에 포함되므로 본체(HTTP+DB)가 각각 다시 조회된다
        verify(investmentPointsPort, times(2)).fetch(any(), any(), any(), any(), any(), any());
        verify(housingPriceIndexPort, times(2)).findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any());
    }

    @Test
    void 벤치마크_본체_계산_실패는_병렬_래핑_없이_원본_예외를_그대로_전파한다() {
        stubPortfolioWeekly(List.of(
                point("2026-01-05", "100.00"),
                point("2026-02-23", "184.20")), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23));
        RuntimeException boom = new IllegalStateException("벤치마크 조회 실패");
        when(housingPriceIndexPort.findByMetricCodeAndRegionCodeAndBaseDateBetween(
                anyString(), anyString(), any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> statsService.getHousingBenchmarkComparison(
                USER_ID, BenchmarkScope.PORTFOLIO, null, "1100000000",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 23)))
                .isSameAs(boom);
    }
}
