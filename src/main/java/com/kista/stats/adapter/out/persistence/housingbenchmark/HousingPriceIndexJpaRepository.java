package com.kista.stats.adapter.out.persistence.housingbenchmark;

import com.kista.stats.domain.model.HousingBenchmarkRegion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface HousingPriceIndexJpaRepository extends JpaRepository<HousingPriceIndexEntity, UUID> {
    List<HousingPriceIndexEntity> findByMetricCodeAndRegionCodeAndBaseDateBetweenOrderByBaseDateAsc(
            String metricCode, String regionCode, LocalDate from, LocalDate to);

    // 지표별 실제 수집된 지역 카탈로그 — KB Land 응답이 결정하는 값이라 하드코딩 금지
    @Query("select distinct new com.kista.stats.domain.model.HousingBenchmarkRegion(e.regionCode, e.regionName) "
            + "from HousingPriceIndexEntity e where e.metricCode = :metricCode order by e.regionCode")
    List<HousingBenchmarkRegion> findDistinctRegionsByMetricCode(@Param("metricCode") String metricCode);
}
