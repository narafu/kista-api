package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingPriceIndex;
import com.kista.domain.port.out.HousingPriceIndexPort;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HousingPriceIndexPersistenceAdapter implements HousingPriceIndexPort {

    private static final int BATCH_SIZE = 1000; // 주간 지수는 지역 25개 × 20년치라 약 21,900행 — 단일 batchUpdate 방지 위해 청킹

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void upsertAll(List<HousingPriceIndex> indices) {
        // KB Land는 과거 기준일 값이 보정될 수 있어 자연키 충돌 시 최신 응답으로 갱신한다.
        String sql = """
                INSERT INTO housing_price_indices (
                    source,
                    metric_code,
                    region_code,
                    region_name,
                    base_date,
                    index_value,
                    source_updated_date,
                    fetched_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source, metric_code, region_code, base_date) DO UPDATE
                   SET region_name = EXCLUDED.region_name,
                       index_value = EXCLUDED.index_value,
                       source_updated_date = EXCLUDED.source_updated_date,
                       fetched_at = EXCLUDED.fetched_at,
                       updated_at = now()
                """;

        // batchSize를 넘기면 JdbcTemplate이 내부적으로 그 크기마다 flush하므로 별도 청킹 루프 불필요
        jdbcTemplate.batchUpdate(sql, indices, BATCH_SIZE, (ps, index) -> {
            ps.setString(1, index.source());
            ps.setString(2, index.metricCode());
            ps.setString(3, index.regionCode());
            ps.setString(4, index.regionName());
            ps.setObject(5, index.baseDate());
            ps.setBigDecimal(6, index.indexValue());
            ps.setObject(7, index.sourceUpdatedDate());
            ps.setTimestamp(8, Timestamp.from(index.fetchedAt()));
        });
    }
}
