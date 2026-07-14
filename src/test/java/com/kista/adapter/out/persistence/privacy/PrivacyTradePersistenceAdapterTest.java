package com.kista.adapter.out.persistence.privacy;

import com.kista.common.TimeZones;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyTradePersistenceAdapterTest {

    private final PrivacyTradeBaseJpaRepository baseRepository = mock(PrivacyTradeBaseJpaRepository.class);
    private final PrivacyTradePersistenceAdapter adapter = new PrivacyTradePersistenceAdapter(baseRepository);

    @Test
    void findSeedPreviewBase_uses_kst_today_as_preview_start_date() {
        LocalDate todayKst = LocalDate.now(TimeZones.KST);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setTradeDate(todayKst);
        base.setTicker(Ticker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("14467.67"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);

        when(baseRepository.findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(todayKst, Ticker.SOXL))
                .thenReturn(Optional.of(base));

        var result = adapter.findSeedPreviewBase();

        assertThat(result).isPresent();
        assertThat(result.get().currentCycleStart()).isEqualByComparingTo("14467.67");
        verify(baseRepository).findFirstByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(todayKst, Ticker.SOXL);
    }

    @Test
    void findBasesFromTradeDate_returns_release_date_without_kst_conversion() {
        LocalDate dbReleaseDate = LocalDate.of(2026, 7, 1);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setTradeDate(dbReleaseDate);
        base.setTicker(Ticker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("28.50"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);

        when(baseRepository.findBasesFromTradeDate(dbReleaseDate)).thenReturn(List.of(base));

        var result = adapter.findBasesFromTradeDate(dbReleaseDate);

        assertThat(result).singleElement()
                .extracting(view -> view.releaseDate())
                .isEqualTo(dbReleaseDate);
    }

    @Test
    void findTodayTrade_uses_order_fetch_query() {
        LocalDate todayKst = LocalDate.of(2026, 7, 15);
        LocalDate dbTradeDate = LocalDate.of(2026, 7, 14);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setTradeDate(dbTradeDate);
        base.setTicker(Ticker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("28.50"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);
        base.setHoldings(10);

        when(baseRepository.findFirstWithOrdersByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(dbTradeDate, Ticker.SOXL))
                .thenReturn(Optional.of(base));

        var result = adapter.findTodayTrade(todayKst);

        assertThat(result).isPresent();
        verify(baseRepository).findFirstWithOrdersByTradeDateGreaterThanEqualAndTickerOrderByTradeDateAsc(dbTradeDate, Ticker.SOXL);
    }
}
