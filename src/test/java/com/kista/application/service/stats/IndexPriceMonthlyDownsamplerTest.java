package com.kista.application.service.stats;

import com.kista.domain.model.stats.IndexPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexPriceMonthlyDownsamplerTest {

    @Test
    void 여러_달치_데이터에서_각_달의_마지막_거래일_종가를_고른다() {
        List<IndexPrice> prices = List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 2), new BigDecimal("400")),
                new IndexPrice("SPY", LocalDate.of(2026, 1, 30), new BigDecimal("410")),
                new IndexPrice("SPY", LocalDate.of(2026, 2, 3), new BigDecimal("420")),
                new IndexPrice("SPY", LocalDate.of(2026, 2, 27), new BigDecimal("430")));

        Map<LocalDate, BigDecimal> result = IndexPriceMonthlyDownsampler.toMonthlyLastClose(prices);

        assertThat(result).containsExactly(
                Map.entry(LocalDate.of(2026, 1, 1), new BigDecimal("410")),
                Map.entry(LocalDate.of(2026, 2, 1), new BigDecimal("430")));
    }

    @Test
    void 입력_순서와_무관하게_거래일_기준_최신값을_고른다() {
        List<IndexPrice> prices = List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 30), new BigDecimal("410")),
                new IndexPrice("SPY", LocalDate.of(2026, 1, 2), new BigDecimal("400")),
                new IndexPrice("SPY", LocalDate.of(2026, 1, 15), new BigDecimal("405")));

        Map<LocalDate, BigDecimal> result = IndexPriceMonthlyDownsampler.toMonthlyLastClose(prices);

        assertThat(result).containsExactly(Map.entry(LocalDate.of(2026, 1, 1), new BigDecimal("410")));
    }

    @Test
    void 중간_달_데이터가_희소해도_존재하는_달만_반환한다() {
        List<IndexPrice> prices = List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 30), new BigDecimal("410")),
                new IndexPrice("SPY", LocalDate.of(2026, 3, 31), new BigDecimal("430")));

        Map<LocalDate, BigDecimal> result = IndexPriceMonthlyDownsampler.toMonthlyLastClose(prices);

        assertThat(result).containsExactly(
                Map.entry(LocalDate.of(2026, 1, 1), new BigDecimal("410")),
                Map.entry(LocalDate.of(2026, 3, 1), new BigDecimal("430")));
    }

    @Test
    void 한_달에_하루만_있으면_그_값을_그대로_사용한다() {
        List<IndexPrice> prices = List.of(
                new IndexPrice("SPY", LocalDate.of(2026, 1, 15), new BigDecimal("405")));

        Map<LocalDate, BigDecimal> result = IndexPriceMonthlyDownsampler.toMonthlyLastClose(prices);

        assertThat(result).containsExactly(Map.entry(LocalDate.of(2026, 1, 1), new BigDecimal("405")));
    }

    @Test
    void 빈_입력이면_빈_맵을_반환한다() {
        Map<LocalDate, BigDecimal> result = IndexPriceMonthlyDownsampler.toMonthlyLastClose(List.of());

        assertThat(result).isEmpty();
    }
}
