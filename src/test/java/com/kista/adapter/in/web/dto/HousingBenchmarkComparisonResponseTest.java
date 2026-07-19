package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HousingBenchmarkComparisonResponseTest {
    private static final String NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다.";

    @Test
    void quality는_성과_근사치와_통화_기준을_단일_notice로_안내한다() {
        HousingBenchmarkComparison comparison = new HousingBenchmarkComparison(
                BenchmarkScope.PORTFOLIO,
                null,
                new HousingBenchmarkComparison.Benchmark(
                        "1100000000", "서울", 3, "서울 아파트 3분위", null),
                new HousingBenchmarkComparison.Period(null, null, 0),
                null,
                List.of(),
                null,
                "NO_COMMON_MONTHS");

        HousingBenchmarkComparisonResponse response =
                HousingBenchmarkComparisonResponse.from(comparison);

        assertThat(response.quality().notice()).isEqualTo(NOTICE);
    }
}
