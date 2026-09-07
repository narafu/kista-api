package com.kista.privacy.adapter.out.persistence;

import com.kista.common.TimeZones;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.FidaPlannedOrder;
import com.kista.privacy.domain.model.PrivacyDates;
import com.kista.privacy.domain.model.PrivacyOrderDirection;
import com.kista.privacy.domain.model.PrivacyOrderType;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void saveBaseWithOrders_treats_avg_price_within_column_scale_as_idempotent() {
        // 저장된 행: avg_price가 numeric(12,2)라 Postgres가 115.32로 반올림해 보관
        LocalDate releaseDate = LocalDate.of(2026, 9, 8);
        PrivacyTradeBaseEntity base = new PrivacyTradeBaseEntity();
        base.setReleaseDate(releaseDate);
        base.setTicker(StrategyTicker.SOXL);
        base.setCurrentCycleStart(new BigDecimal("14467.67"));
        base.setCurrentCycleRealizedPnl(new BigDecimal("-251.28"));
        base.setAvgPrice(new BigDecimal("115.32"));
        base.setHoldings(16);
        base.getOrders().addAll(List.of(
                order(base, PrivacyOrderDirection.BUY, "114.97", 8),
                order(base, PrivacyOrderDirection.BUY, "118.57", 8),
                order(base, PrivacyOrderDirection.SELL, "119.30", 8)));

        when(baseRepository.findByReleaseDateAndTicker(releaseDate, StrategyTicker.SOXL))
                .thenReturn(Optional.of(base));

        // FIDA 재전송: 금액 필드가 재계산돼 소수 3자리 — 저장 스케일(2자리)로는 전부 동일
        // (음수 pnl HALF_UP 반올림 경로도 함께 검증: -251.284 → -251.28)
        FidaOrderCommand command = new FidaOrderCommand(
                releaseDate, StrategyTicker.SOXL,
                new BigDecimal("14467.674"), new BigDecimal("-251.284"),
                new BigDecimal("115.318"), 16,
                List.of(
                        new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, 8, new BigDecimal("114.97")),
                        new FidaPlannedOrder(PrivacyOrderDirection.BUY, PrivacyOrderType.LIMIT, 8, new BigDecimal("118.57")),
                        new FidaPlannedOrder(PrivacyOrderDirection.SELL, PrivacyOrderType.LIMIT, 8, new BigDecimal("119.30"))));

        var result = adapter.saveBaseWithOrders(command); // 409 던지지 않음

        assertThat(result.created()).isFalse(); // 멱등 — 200
        assertThat(result.id()).isEqualTo(base.getId());
        verify(baseRepository, never()).save(any());
    }

    private static PrivacyTradeBaseOrderEntity order(PrivacyTradeBaseEntity base, PrivacyOrderDirection dir, String price, int qty) {
        PrivacyTradeBaseOrderEntity o = new PrivacyTradeBaseOrderEntity();
        o.setPrivacyBase(base);
        o.setDirection(dir);
        o.setOrderType(PrivacyOrderType.LIMIT);
        o.setPrice(new BigDecimal(price));
        o.setQuantity(qty);
        return o;
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
