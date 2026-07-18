package com.kista.adapter.out.persistence.housingbenchmark;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(HousingBenchmarkPricePersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class HousingBenchmarkPricePersistenceAdapterTest extends DataJpaTestBase {

    @Autowired HousingBenchmarkPricePersistenceAdapter adapter;

    @Test
    void upsertAll_insertsAndFindsByMetricRegionAndMonthAscending() {
        HousingBenchmarkPrice june = price("서울", "1100000000", LocalDate.of(2026, 6, 1), "52600.990329");
        HousingBenchmarkPrice may = price("서울", "1100000000", LocalDate.of(2026, 5, 1), "51000.000000");
        HousingBenchmarkPrice national = price("전국", "0000000000", LocalDate.of(2026, 6, 1), "11515.151530");

        adapter.upsertAll(List.of(june, may, national));

        List<HousingBenchmarkPrice> result = adapter.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                "APT_QTE_SALE_PRICE", "1100000000", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));

        assertThat(result).extracting(HousingBenchmarkPrice::baseMonth)
                .containsExactly(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));
        assertThat(result).extracting(HousingBenchmarkPrice::regionName)
                .containsExactly("서울", "서울");
    }

    @Test
    void upsertAll_updatesExistingRegionMonthWhenKbLandRevisesValue() {
        LocalDate baseMonth = LocalDate.of(2026, 6, 1);
        adapter.upsertAll(List.of(price("서울", "1100000000", baseMonth, "52600.990329")));

        adapter.upsertAll(List.of(price("서울특별시", "1100000000", baseMonth, "53000.123456")));

        List<HousingBenchmarkPrice> result = adapter.findByMetricCodeAndRegionCodeAndBaseMonthBetween(
                "APT_QTE_SALE_PRICE", "1100000000", baseMonth, baseMonth);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).regionName()).isEqualTo("서울특별시");
        assertThat(result.get(0).firstQuintilePrice()).isEqualByComparingTo(new BigDecimal("53000.123456"));
    }

    private static HousingBenchmarkPrice price(String regionName, String regionCode, LocalDate baseMonth, String firstQuintile) {
        return new HousingBenchmarkPrice(
                "KBLAND",
                "APT_QTE_SALE_PRICE",
                regionCode,
                regionName,
                baseMonth,
                new BigDecimal(firstQuintile),
                new BigDecimal("86000.000000"),
                new BigDecimal("126000.000000"),
                new BigDecimal("181000.000000"),
                new BigDecimal("344000.000000"),
                new BigDecimal("6.500000000000"),
                LocalDate.of(2026, 6, 15),
                Instant.parse("2026-06-20T00:00:00Z")
        );
    }
}
