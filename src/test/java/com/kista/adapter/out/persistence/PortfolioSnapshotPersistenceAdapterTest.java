package com.kista.adapter.out.persistence;

import com.kista.domain.model.PortfolioSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PortfolioSnapshotPersistenceAdapterTest {

    @Autowired
    private PortfolioSnapshotPersistenceAdapter adapter;

    private PortfolioSnapshot snapshot(LocalDate date) {
        return new PortfolioSnapshot(
                null, date, "SOXL", 10,
                new BigDecimal("20.0000"), new BigDecimal("22.0000"),
                new BigDecimal("220.00"), new BigDecimal("500.00"),
                new BigDecimal("720.00"), null
        );
    }

    @Test
    void save_and_findRecent_returns_matching_record() {
        LocalDate today = LocalDate.now();
        adapter.save(snapshot(today));

        List<PortfolioSnapshot> result = adapter.findRecent(7);

        assertThat(result).hasSize(1);
        PortfolioSnapshot saved = result.get(0);
        assertThat(saved.snapshotDate()).isEqualTo(today);
        assertThat(saved.symbol()).isEqualTo("SOXL");
        assertThat(saved.qty()).isEqualTo(10);
        assertThat(saved.avgPrice()).isEqualByComparingTo("20.0000");
        assertThat(saved.currentPrice()).isEqualByComparingTo("22.0000");
        assertThat(saved.marketValueUsd()).isEqualByComparingTo("220.00");
        assertThat(saved.usdDeposit()).isEqualByComparingTo("500.00");
        assertThat(saved.totalAssetUsd()).isEqualByComparingTo("720.00");
    }

    @Test
    void findRecent_excludes_old_records() {
        LocalDate today = LocalDate.now();
        LocalDate old = today.minusDays(8);

        adapter.save(snapshot(today));
        adapter.save(snapshot(old));

        List<PortfolioSnapshot> result = adapter.findRecent(7);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).snapshotDate()).isEqualTo(today);
    }

    @Test
    void findRecent_returns_desc_order() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate twoDaysAgo = today.minusDays(2);

        adapter.save(snapshot(twoDaysAgo));
        adapter.save(snapshot(yesterday));
        adapter.save(snapshot(today));

        List<PortfolioSnapshot> result = adapter.findRecent(7);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).snapshotDate()).isEqualTo(today);
        assertThat(result.get(1).snapshotDate()).isEqualTo(yesterday);
        assertThat(result.get(2).snapshotDate()).isEqualTo(twoDaysAgo);
    }
}
