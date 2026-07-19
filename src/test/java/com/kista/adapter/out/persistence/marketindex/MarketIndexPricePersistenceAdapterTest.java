package com.kista.adapter.out.persistence.marketindex;

import com.kista.domain.model.stats.IndexPrice;
import com.kista.support.DataJpaTestBase;
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
        assertThat(spyOnly.get(0).symbol()).isEqualTo("SPY");
        assertThat(spyOnly.get(0).close()).isEqualByComparingTo(new BigDecimal("500.00"));
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
}
