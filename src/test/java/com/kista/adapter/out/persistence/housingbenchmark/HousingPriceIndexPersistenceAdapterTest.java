package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingPriceIndex;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(HousingPriceIndexPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class HousingPriceIndexPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired HousingPriceIndexPersistenceAdapter adapter;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void upsertAll_updatesExistingRegionDateWhenKbLandRevisesValue() {
        LocalDate baseDate = LocalDate.of(2026, 7, 6);
        adapter.upsertAll(List.of(index("서울", "1100000000", baseDate, "100.000000000000")));

        adapter.upsertAll(List.of(index("서울특별시", "1100000000", baseDate, "101.500000000000")));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from housing_price_indices where region_code = ? and base_date = ?",
                Integer.class, "1100000000", baseDate);
        assertThat(count).isEqualTo(1);

        String regionName = jdbcTemplate.queryForObject(
                "select region_name from housing_price_indices where region_code = ? and base_date = ?",
                String.class, "1100000000", baseDate);
        assertThat(regionName).isEqualTo("서울특별시");

        BigDecimal indexValue = jdbcTemplate.queryForObject(
                "select index_value from housing_price_indices where region_code = ? and base_date = ?",
                BigDecimal.class, "1100000000", baseDate);
        assertThat(indexValue).isEqualByComparingTo(new BigDecimal("101.500000000000"));
    }

    @Test
    void upsertAll_persistsAllRowsBeyondSingleBatchSize() {
        // BATCH_SIZE(1000)를 초과하는 1500행 upsert가 전량 저장되는지 검증한다(JdbcTemplate이 batchSize마다 내부 flush).
        List<HousingPriceIndex> indices = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            indices.add(index("서울", "1100000000", LocalDate.of(2020, 1, 1).plusWeeks(i),
                    String.valueOf(100 + i) + ".000000000000"));
        }

        adapter.upsertAll(indices);

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from housing_price_indices where region_code = ?", Integer.class, "1100000000");
        assertThat(count).isEqualTo(1500);
    }

    private static HousingPriceIndex index(String regionName, String regionCode, LocalDate baseDate, String indexValue) {
        return new HousingPriceIndex(
                "KBLAND",
                "WEEKLY_APT_SALE_PRICE_INDEX",
                regionCode,
                regionName,
                baseDate,
                new BigDecimal(indexValue),
                LocalDate.of(2026, 8, 3),
                Instant.parse("2026-08-03T00:00:00Z")
        );
    }
}
