package com.kista.adapter.out.persistence.housingbenchmark;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface HousingBenchmarkPriceJpaRepository extends JpaRepository<HousingBenchmarkPriceEntity, UUID> {
    List<HousingBenchmarkPriceEntity> findByMetricCodeAndRegionCodeAndBaseMonthBetweenOrderByBaseMonthAsc(
            String metricCode, String regionCode, LocalDate from, LocalDate to);
}
