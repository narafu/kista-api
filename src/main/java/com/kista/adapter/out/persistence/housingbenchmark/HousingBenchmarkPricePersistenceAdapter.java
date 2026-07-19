package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.domain.model.stats.HousingBenchmarkRegion;
import com.kista.domain.port.out.HousingBenchmarkPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HousingBenchmarkPricePersistenceAdapter implements HousingBenchmarkPricePort {

    private final HousingBenchmarkPriceJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsertAll(List<HousingBenchmarkPrice> prices) {
        // KB Land는 과거 기준 월 값이 보정될 수 있어 자연키 충돌 시 최신 응답으로 갱신한다.
        for (HousingBenchmarkPrice price : prices) {
            jdbcTemplate.update("""
                    INSERT INTO housing_benchmark_prices (
                        source,
                        metric_code,
                        region_code,
                        region_name,
                        base_month,
                        first_quintile_price,
                        second_quintile_price,
                        third_quintile_price,
                        fourth_quintile_price,
                        fifth_quintile_price,
                        fifth_quintile_ratio,
                        source_updated_date,
                        fetched_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (source, metric_code, region_code, base_month) DO UPDATE
                       SET region_name = EXCLUDED.region_name,
                           first_quintile_price = EXCLUDED.first_quintile_price,
                           second_quintile_price = EXCLUDED.second_quintile_price,
                           third_quintile_price = EXCLUDED.third_quintile_price,
                           fourth_quintile_price = EXCLUDED.fourth_quintile_price,
                           fifth_quintile_price = EXCLUDED.fifth_quintile_price,
                           fifth_quintile_ratio = EXCLUDED.fifth_quintile_ratio,
                           source_updated_date = EXCLUDED.source_updated_date,
                           fetched_at = EXCLUDED.fetched_at,
                           updated_at = now()
                    """,
                    price.source(),
                    price.metricCode(),
                    price.regionCode(),
                    price.regionName(),
                    price.baseMonth(),
                    price.firstQuintilePrice(),
                    price.secondQuintilePrice(),
                    price.thirdQuintilePrice(),
                    price.fourthQuintilePrice(),
                    price.fifthQuintilePrice(),
                    price.fifthQuintileRatio(),
                    price.sourceUpdatedDate(),
                    Timestamp.from(price.fetchedAt())
            );
        }
    }

    @Override
    public List<HousingBenchmarkPrice> findByMetricCodeAndRegionCodeAndBaseMonthBetween(
            String metricCode, String regionCode, LocalDate from, LocalDate to) {
        return repository.findByMetricCodeAndRegionCodeAndBaseMonthBetweenOrderByBaseMonthAsc(
                        metricCode, regionCode, from, to)
                .stream()
                .map(HousingBenchmarkPriceEntity::toDomain)
                .toList();
    }

    @Override
    public List<HousingBenchmarkRegion> findDistinctRegions(String metricCode) {
        return repository.findDistinctRegionsByMetricCode(metricCode);
    }
}
