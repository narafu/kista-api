package com.kista.api.benchmark.adapter.in.web.dto;

import com.kista.api.benchmark.domain.model.BenchmarkAssetType;
import com.kista.api.benchmark.domain.model.BenchmarkScope;
import com.kista.api.benchmark.domain.model.BenchmarkComparison;
import com.kista.api.benchmark.domain.model.StrategyRef;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkComparisonResponseTest {
    private static final String HOUSING_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 아파트 매매가격지수는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다. "
                    + "벤치마크 시점은 KB Land 주간 조사일(매주 월요일) 기준입니다.";
    private static final String ETF_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자와 ETF 벤치마크 모두 USD 기준이며 환율 변수가 없습니다.";

    @Test
    void quality는_성과_근사치와_통화_기준을_단일_notice로_안내한다() {
        BenchmarkComparison comparison = new BenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                Currency.USD,
                new BenchmarkComparison.Benchmark(
                        BenchmarkAssetType.HOUSING, "1100000000", "서울",
                        null, "서울 아파트 매매가격지수", null),
                new BenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_COMMON_MONTHS");

        BenchmarkComparisonResponse response =
                BenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().notice()).isEqualTo(HOUSING_NOTICE);
        assertThat(response.quality().benchmarkCurrency()).isEqualTo("KRW");
    }

    @Test
    void PORTFOLIO_USDT는_investmentCurrency를_USDT로_표기한다() {
        BenchmarkComparison comparison = new BenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                Currency.USDT,
                new BenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, "SPY",
                        "SPY (SPDR S&P 500 ETF Trust)", null),
                new BenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_INVESTMENT_DATA");

        BenchmarkComparisonResponse response = BenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().investmentCurrency()).isEqualTo("USDT");
        assertThat(response.quality().notice()).contains("USDT");
    }

    @Test
    void ETF_비교는_USD_통화와_전용_notice를_반환한다() {
        BenchmarkComparison comparison = new BenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                Currency.USD,
                new BenchmarkComparison.Benchmark(
                        BenchmarkAssetType.ETF, null, null, "SPY",
                        "SPY (SPDR S&P 500 ETF Trust)", null),
                new BenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_COMMON_MONTHS");

        BenchmarkComparisonResponse response =
                BenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().notice()).isEqualTo(ETF_NOTICE);
        assertThat(response.quality().benchmarkCurrency()).isEqualTo("USD");
        assertThat(response.benchmark().assetType()).isEqualTo("ETF");
        assertThat(response.benchmark().symbol()).isEqualTo("SPY");
        assertThat(response.quality().investmentCurrency()).isEqualTo("USD");
    }

    @Test
    void USDT_전략_ETF_비교는_투자_통화를_USDT로_표기한다() {
        BenchmarkComparisonResponse response = BenchmarkComparisonResponse.from(
                strategyComparison(Currency.USDT, BenchmarkAssetType.ETF));

        assertThat(response.quality().investmentCurrency()).isEqualTo("USDT");
        assertThat(response.quality().benchmarkCurrency()).isEqualTo("USD");
        assertThat(response.quality().notice()).contains("투자 성과는 USDT");
    }

    @Test
    void USDT_전략_아파트_비교는_투자_통화를_USDT로_표기한다() {
        BenchmarkComparisonResponse response = BenchmarkComparisonResponse.from(
                strategyComparison(Currency.USDT, BenchmarkAssetType.HOUSING));

        assertThat(response.quality().investmentCurrency()).isEqualTo("USDT");
        assertThat(response.quality().notice()).contains("투자 성과는 USDT, 아파트 매매가격지수는 KRW");
    }

    @Test
    void KRW_전략_아파트_비교는_같은_통화임을_안내한다() {
        BenchmarkComparisonResponse response = BenchmarkComparisonResponse.from(
                strategyComparison(Currency.KRW, BenchmarkAssetType.HOUSING));

        assertThat(response.quality().investmentCurrency()).isEqualTo("KRW");
        assertThat(response.quality().notice()).contains("모두 KRW 기준");
    }

    @Test
    void USD_전략은_기존_ETF_notice를_유지한다() {
        BenchmarkComparisonResponse response = BenchmarkComparisonResponse.from(
                strategyComparison(Currency.USD, BenchmarkAssetType.ETF));

        assertThat(response.quality().investmentCurrency()).isEqualTo("USD");
        assertThat(response.quality().notice()).isEqualTo(ETF_NOTICE);
    }

    // 데이터 없는(emptyReason) STRATEGY 비교 — 빈 결과에서도 전략 통화가 표기돼야 한다
    private static BenchmarkComparison strategyComparison(Currency currency, BenchmarkAssetType assetType) {
        boolean etf = assetType == BenchmarkAssetType.ETF;
        return new BenchmarkComparison(
                BenchmarkScope.STRATEGY,
                new StrategyRef(UUID.randomUUID(), StrategyType.DCA, StrategyTicker.ETH, currency),
                currency,
                new BenchmarkComparison.Benchmark(
                        assetType, etf ? null : "1100000000", etf ? null : "서울",
                        etf ? "SPY" : null, "label", null),
                new BenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_INVESTMENT_DATA");
    }
}
