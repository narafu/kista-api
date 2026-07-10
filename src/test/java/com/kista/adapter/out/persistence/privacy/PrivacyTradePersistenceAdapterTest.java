package com.kista.adapter.out.persistence.privacy;

import com.kista.common.TimeZones;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
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
}
