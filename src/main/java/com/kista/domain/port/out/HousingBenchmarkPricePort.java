package com.kista.domain.port.out;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.model.stats.HousingBenchmarkRegion;

import java.time.LocalDate;
import java.util.List;

public interface HousingBenchmarkPricePort {
    // 자연키(source+metric+region+baseMonth) 기준 저장 또는 갱신
    void upsertAll(List<HousingBenchmarkPrice> prices);

    // 통계 화면 연결을 위한 저장분 조회
    List<HousingBenchmarkPrice> findByMetricCodeAndRegionCodeAndBaseMonthBetween(
            String metricCode, String regionCode, LocalDate from, LocalDate to);

    // 실제 수집된 지역 카탈로그 (source+metric 기준 distinct region_code/region_name)
    List<HousingBenchmarkRegion> findDistinctRegions(String metricCode);
}
