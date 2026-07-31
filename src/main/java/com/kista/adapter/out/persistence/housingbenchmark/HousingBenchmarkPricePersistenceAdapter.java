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
        // batchUpdate로 1회 왕복으로 처리 (행마다 개별 update 제거)
        String sql = """
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
                """;

        jdbcTemplate.batchUpdate(sql, prices, prices.size(), (ps, price) -> {
            ps.setString(1, price.source());
            ps.setString(2, price.metricCode());
            ps.setString(3, price.regionCode());
            ps.setString(4, price.regionName());
            ps.setObject(5, price.baseMonth());
            ps.setBigDecimal(6, price.firstQuintilePrice());
            ps.setBigDecimal(7, price.secondQuintilePrice());
            ps.setBigDecimal(8, price.thirdQuintilePrice());
            ps.setBigDecimal(9, price.fourthQuintilePrice());
            ps.setBigDecimal(10, price.fifthQuintilePrice());
            ps.setBigDecimal(11, price.fifthQuintileRatio());
            ps.setObject(12, price.sourceUpdatedDate());
            ps.setTimestamp(13, Timestamp.from(price.fetchedAt()));
        });
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
