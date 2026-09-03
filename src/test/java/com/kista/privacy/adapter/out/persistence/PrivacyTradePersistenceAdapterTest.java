package com.kista.privacy.adapter.out.persistence;

import com.kista.common.TimeZones;
import com.kista.privacy.domain.model.PrivacyDates;
import com.kista.sharedkernel.StrategyTicker;
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
    void findSeedPreviewBase_queries_by_release_date_for_kst_today() {
        LocalDate todayKst = LocalDate.now(TimeZones.KST);
        // 버그 수정 검증: 오늘 거래일에 적용되는 발행일(전날)부터 조회해야 함 — 오늘 발행일로 조회하면 하루 누락
        LocalDate expectedReleaseDate = PrivacyDates.releaseDateFor(todayKst);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setReleaseDate(expectedReleaseDate);
        base.setTicker(StrategyTicker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("14467.67"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);

        when(baseRepository.findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(expectedReleaseDate, StrategyTicker.SOXL))
                .thenReturn(Optional.of(base));

        var result = adapter.findSeedPreviewBase();

        assertThat(result).isPresent();
        assertThat(result.get().currentCycleStart()).isEqualByComparingTo("14467.67");
        assertThat(result.get().tradeDate()).isEqualTo(todayKst); // 발행일 → 적용 거래일 변환 확인
        verify(baseRepository).findFirstByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(expectedReleaseDate, StrategyTicker.SOXL);
    }

    @Test
    void findBasesFromTradeDate_returns_release_date_without_kst_conversion() {
        LocalDate dbReleaseDate = LocalDate.of(2026, 7, 1);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setReleaseDate(dbReleaseDate);
        base.setTicker(StrategyTicker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("28.50"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);

        when(baseRepository.findBasesFromReleaseDate(dbReleaseDate)).thenReturn(List.of(base));

        var result = adapter.findBasesFromTradeDate(dbReleaseDate);

        assertThat(result).singleElement()
                .extracting(view -> view.releaseDate())
                .isEqualTo(dbReleaseDate);
    }

    @Test
    void findTodayTrade_uses_order_fetch_query() {
        LocalDate todayKst = LocalDate.of(2026, 7, 15);
        LocalDate dbReleaseDate = LocalDate.of(2026, 7, 14);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setReleaseDate(dbReleaseDate);
        base.setTicker(StrategyTicker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("28.50"));
        base.setCurrentCycleRealizedPnl(BigDecimal.ZERO);
        base.setHoldings(10);

        when(baseRepository.findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                        PrivacyDates.releaseDateFor(todayKst), StrategyTicker.SOXL))
                .thenReturn(Optional.of(base));

        var result = adapter.findTodayTrade(todayKst);

        assertThat(result).isPresent();
        verify(baseRepository).findFirstWithOrdersByReleaseDateGreaterThanEqualAndTickerOrderByReleaseDateAsc(
                PrivacyDates.releaseDateFor(todayKst), StrategyTicker.SOXL);
    }

}
