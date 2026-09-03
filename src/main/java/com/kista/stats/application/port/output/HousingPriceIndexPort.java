package com.kista.stats.application.port.output;

import com.kista.stats.domain.model.HousingBenchmarkRegion;
import com.kista.stats.domain.model.HousingPriceIndex;

import java.time.LocalDate;
import java.util.List;

public interface HousingPriceIndexPort {
    // 자연키(source+metric+region+baseDate) 기준 저장 또는 갱신
    void upsertAll(List<HousingPriceIndex> indices);

    // 통계 화면 연결을 위한 저장분 조회
    List<HousingPriceIndex> findByMetricCodeAndRegionCodeAndBaseDateBetween(
            String metricCode, String regionCode, LocalDate from, LocalDate to);

    // 실제 수집된 지역 카탈로그 (source+metric 기준 distinct region_code/region_name)
    List<HousingBenchmarkRegion> findDistinctRegions(String metricCode);
}
