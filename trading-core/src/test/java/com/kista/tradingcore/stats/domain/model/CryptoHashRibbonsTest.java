package com.kista.tradingcore.stats.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoHashRibbonsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDate START = TODAY.minusDays(300);

    // start부터 연속 일자 시계열
    private static List<DatedValue> series(LocalDate start, double... values) {
        List<DatedValue> list = new ArrayList<>();
        for (int i = 0; i < values.length; i++) list.add(new DatedValue(start.plusDays(i), BigDecimal.valueOf(values[i])));
        return list;
    }

    private static double[] repeat(double v, int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static double[] concat(double[]... parts) {
        return java.util.Arrays.stream(parts).flatMapToDouble(java.util.Arrays::stream).toArray();
    }

    @Test
    void 이력_60일_미만_일자는_state_null_buySignal_false() {
        List<DatedHashRibbon> r = CryptoHashRibbons.history(series(START, repeat(100, 59)), TODAY, 100);

        assertThat(r).hasSize(59).allSatisfy(p -> {
            assertThat(p.sma60()).isNull();
            assertThat(p.state()).isNull();
            assertThat(p.buySignal()).isFalse();
        });
        assertThat(r.getLast().sma30()).isEqualByComparingTo("100.00");
    }

    @Test
    void sma30이_sma60과_같으면_RECOVERY() {
        DatedHashRibbon last = CryptoHashRibbons.history(series(START, repeat(100, 60)), TODAY, 100).getLast();

        assertThat(last.sma30()).isEqualByComparingTo("100.00");
        assertThat(last.sma60()).isEqualByComparingTo("100.00");
        assertThat(last.state()).isEqualTo(DatedHashRibbon.State.RECOVERY);
        assertThat(last.buySignal()).isFalse();
    }

    @Test
    void 반올림_후_같아도_원본_평균이_sma30_미만이면_CAPITULATION() {
        // sma30=100.000, sma60=100.004 → 둘 다 100.00으로 반올림되지만 sma30 < sma60
        double[] v = concat(repeat(100.008, 30), repeat(100.000, 30));
        DatedHashRibbon last = CryptoHashRibbons.history(series(START, v), TODAY, 100).getLast();

        assertThat(last.sma30()).isEqualByComparingTo("100.00");
        assertThat(last.sma60()).isEqualByComparingTo("100.00");
        assertThat(last.state()).isEqualTo(DatedHashRibbon.State.CAPITULATION);
    }

    @Test
    void 하락하면_CAPITULATION_회복_교차일만_buySignal() {
        List<DatedHashRibbon> r = CryptoHashRibbons.history(
                series(START, concat(repeat(100, 60), repeat(50, 20), repeat(200, 40))), TODAY, 200);

        assertThat(r.get(79).state()).isEqualTo(DatedHashRibbon.State.CAPITULATION);
        assertThat(r.stream().filter(DatedHashRibbon::buySignal)).hasSize(1);
        int signal = r.indexOf(r.stream().filter(DatedHashRibbon::buySignal).findFirst().orElseThrow());
        assertThat(r.get(signal).state()).isEqualTo(DatedHashRibbon.State.RECOVERY);
        assertThat(r.get(signal - 1).state()).isEqualTo(DatedHashRibbon.State.CAPITULATION);
    }

    @Test
    void 오늘_이후_점은_제외() {
        List<DatedValue> in = series(TODAY.minusDays(3), 1, 1, 1, 1, 1); // today-3 .. today+1

        assertThat(CryptoHashRibbons.history(in, TODAY, 10).getLast().date()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void days만큼_뒤에서_자르되_SMA는_전체로_계산() {
        List<DatedHashRibbon> r = CryptoHashRibbons.history(series(START, repeat(100, 120)), TODAY, 10);

        assertThat(r).hasSize(10);
        assertThat(r.getFirst().sma60()).isEqualByComparingTo("100.00");
    }

    @Test
    void 완성_점이_없으면_예외() {
        assertThatThrownBy(() -> CryptoHashRibbons.history(List.of(), TODAY, 10)).isInstanceOf(IllegalStateException.class);
    }
}
