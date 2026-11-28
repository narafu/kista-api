package com.kista.api.benchmark.adapter.out.persistence.marketindex;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.domain.model.SplitAdjustment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(MarketIndexPricePersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class MarketIndexPricePersistenceAdapterTest extends DataJpaTestBase {

    @Autowired MarketIndexPricePersistenceAdapter adapter;

    @Test
    void saveAll_thenFindBySymbolAndRange_returnsAscendingByTradeDate() {
        LocalDate base = LocalDate.of(2026, 7, 1);

        // 등록 순서를 뒤섞어 tradeDate ASC 정렬이 insert 순서가 아님을 검증
        adapter.saveAll(List.of(
                new IndexPrice("SPY", base.plusDays(2), new BigDecimal("530.00")),
                new IndexPrice("SPY", base, new BigDecimal("500.00")),
                new IndexPrice("SPY", base.plusDays(1), new BigDecimal("510.00"))
        ));

        List<IndexPrice> result = adapter.findBySymbolAndRange("SPY", base, base.plusDays(2));

        assertThat(result).extracting(IndexPrice::tradeDate)
                .containsExactly(base, base.plusDays(1), base.plusDays(2));
        assertThat(result).extracting(IndexPrice::close)
                .containsExactly(new BigDecimal("500.00"), new BigDecimal("510.00"), new BigDecimal("530.00"));
    }

    @Test
    void findBySymbolAndRange_separatesBySymbolAndRespectsBoundaries() {
        LocalDate base = LocalDate.of(2026, 7, 1);

        adapter.saveAll(List.of(
                new IndexPrice("SPY", base, new BigDecimal("500.00")),
                new IndexPrice("QQQ", base, new BigDecimal("400.00")),
                new IndexPrice("SPY", base.minusDays(1), new BigDecimal("495.00")), // 범위 밖 (하한 이전)
                new IndexPrice("SPY", base.plusDays(1), new BigDecimal("505.00"))   // 범위 밖 (상한 이후)
        ));

        List<IndexPrice> spyOnly = adapter.findBySymbolAndRange("SPY", base, base);

        assertThat(spyOnly).hasSize(1);
        assertThat(spyOnly.getFirst().symbol()).isEqualTo("SPY");
        assertThat(spyOnly.getFirst().close()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void findMaxTradeDate_returnsLatestPersistedDate() {
        adapter.saveAll(List.of(
                new IndexPrice("QQQ", LocalDate.of(2026, 7, 1), new BigDecimal("400.00")),
                new IndexPrice("QQQ", LocalDate.of(2026, 7, 3), new BigDecimal("410.00")),
                new IndexPrice("QQQ", LocalDate.of(2026, 7, 2), new BigDecimal("405.00"))
        ));

        Optional<LocalDate> maxDate = adapter.findMaxTradeDate("QQQ");

        assertThat(maxDate).contains(LocalDate.of(2026, 7, 3));
    }

    @Test
    void findMaxTradeDate_returnsEmptyWhenNoDataForSymbol() {
        assertThat(adapter.findMaxTradeDate("TQQQ")).isEmpty();
    }

    @Test
    void saveAll_sameDateOverwritesCloseAndInsertsNewDates() {
        LocalDate base = LocalDate.of(2026, 7, 1);
        adapter.saveAll(List.of(new IndexPrice("QLD", base, new BigDecimal("63.62"))));

        adapter.saveAll(List.of(
                new IndexPrice("QLD", base, new BigDecimal("63.52")),
                new IndexPrice("QLD", base.plusDays(1), new BigDecimal("64.00"))));

        assertThat(adapter.findBySymbolAndRange("QLD", base, base.plusDays(1)))
                .extracting(IndexPrice::close)
                .containsExactly(new BigDecimal("63.52"), new BigDecimal("64.00"));
    }

    @Test
    void applySplit_scalesOnlyRowsThroughDateOfThatSymbolAndRounds() {
        LocalDate base = LocalDate.of(2026, 10, 1);
        adapter.saveAll(List.of(
                new IndexPrice("ETHA", base, new BigDecimal("20.37")),
                new IndexPrice("ETHA", base.plusDays(1), new BigDecimal("20.11")),
                new IndexPrice("ETHA", base.plusDays(4), new BigDecimal("61.29")),  // 분할 뒤 — 그대로
                new IndexPrice("IBIT", base, new BigDecimal("50.00"))));             // 다른 종목 — 그대로

        int updated = adapter.applySplit("ETHA", new SplitAdjustment(base.plusDays(1), 3, 1));
        adapter.applySplit("IBIT", new SplitAdjustment(base.minusDays(1), 1, 3)); // through 이전 행 없음

        assertThat(updated).isEqualTo(2);
        assertThat(adapter.findBySymbolAndRange("ETHA", base, base.plusDays(4)))
                .extracting(IndexPrice::close)
                .containsExactly(new BigDecimal("61.11"), new BigDecimal("60.33"), new BigDecimal("61.29"));
        assertThat(adapter.findBySymbolAndRange("IBIT", base, base).getFirst().close()).isEqualByComparingTo("50.00");
    }

    @Test
    void applySplit_divisionRoundsHalfUpToTwoDecimals() {
        LocalDate base = LocalDate.of(2026, 10, 1);
        adapter.saveAll(List.of(new IndexPrice("SPY", base, new BigDecimal("100.01"))));

        adapter.applySplit("SPY", new SplitAdjustment(base, 1, 3));

        // 100.01 / 3 = 33.3366… → 33.34
        assertThat(adapter.findBySymbolAndRange("SPY", base, base).getFirst().close()).isEqualByComparingTo("33.34");
    }
}
