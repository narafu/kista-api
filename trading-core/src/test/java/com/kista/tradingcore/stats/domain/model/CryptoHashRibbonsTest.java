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
        List<DatedHashRibbon> r = CryptoHashRibbons.history(series(START, repeat(100, 59)), List.of(), TODAY, 100);

        assertThat(r).hasSize(59).allSatisfy(p -> {
            assertThat(p.sma60()).isNull();
            assertThat(p.state()).isNull();
            assertThat(p.buySignal()).isFalse();
        });
        assertThat(r.getLast().sma30()).isEqualByComparingTo("100.00");
    }

    @Test
    void sma30이_sma60과_같으면_RECOVERY() {
        DatedHashRibbon last = CryptoHashRibbons.history(series(START, repeat(100, 60)), List.of(), TODAY, 100).getLast();

        assertThat(last.sma30()).isEqualByComparingTo("100.00");
        assertThat(last.sma60()).isEqualByComparingTo("100.00");
        assertThat(last.state()).isEqualTo(DatedHashRibbon.State.RECOVERY);
        assertThat(last.buySignal()).isFalse();
    }

    @Test
    void 반올림_후_같아도_원본_평균이_sma30_미만이면_CAPITULATION() {
        // sma30=100.000, sma60=100.004 → 둘 다 100.00으로 반올림되지만 sma30 < sma60
        double[] v = concat(repeat(100.008, 30), repeat(100.000, 30));
        DatedHashRibbon last = CryptoHashRibbons.history(series(START, v), List.of(), TODAY, 100).getLast();

        assertThat(last.sma30()).isEqualByComparingTo("100.00");
        assertThat(last.sma60()).isEqualByComparingTo("100.00");
        assertThat(last.state()).isEqualTo(DatedHashRibbon.State.CAPITULATION);
    }

    // 하락 후 회복하는 해시레이트: 0..59 정상, 60..79 항복, 80.. 회복(전환일은 그 안의 첫 RECOVERY)
    private static final double[] RECOVERING = concat(repeat(100, 60), repeat(50, 20), repeat(200, 40));

    // START부터 인덱스 i의 가격을 f(i)로 채운 일별 종가
    private static List<DatedValue> prices(int n, java.util.function.IntToDoubleFunction f) {
        List<DatedValue> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new DatedValue(START.plusDays(i), BigDecimal.valueOf(f.applyAsDouble(i))));
        return list;
    }

    private static List<Integer> signals(List<DatedHashRibbon> r) {
        return java.util.stream.IntStream.range(0, r.size()).filter(i -> r.get(i).buySignal()).boxed().toList();
    }

    // 항복→회복 전환일 인덱스
    private static int transition(List<DatedHashRibbon> r) {
        for (int i = 1; i < r.size(); i++)
            if (r.get(i - 1).state() == DatedHashRibbon.State.CAPITULATION && r.get(i).state() == DatedHashRibbon.State.RECOVERY) return i;
        throw new AssertionError("전환일 없음");
    }

    @Test
    void 전환일에_가격_모멘텀이_이미_충족이면_그날_buySignal() {
        List<DatedHashRibbon> r = CryptoHashRibbons.history(
                series(START, RECOVERING), prices(120, i -> 100 + i), TODAY, 200); // 계속 상승 — 10일 SMA > 20일 SMA

        assertThat(r.get(79).state()).isEqualTo(DatedHashRibbon.State.CAPITULATION);
        assertThat(signals(r)).containsExactly(transition(r));
    }

    @Test
    void 전환일에_모멘텀이_꺾여있으면_모멘텀이_살아나는_날로_미루고_그날만_buySignal() {
        int t = transition(CryptoHashRibbons.history(series(START, RECOVERING), List.of(), TODAY, 200));
        // 하락 추세 → t+3일에 급등해 10일 SMA가 20일 SMA를 넘는다
        List<DatedHashRibbon> r = CryptoHashRibbons.history(
                series(START, RECOVERING), prices(120, i -> i < t + 3 ? 1000 - i : 100000), TODAY, 200);

        assertThat(signals(r)).containsExactly(t + 3);
        assertThat(r.get(t + 3).state()).isEqualTo(DatedHashRibbon.State.RECOVERY);
    }

    @Test
    void 모멘텀이_살아나기_전에_다시_항복하면_신호_없음() {
        // 회복 5일 만에 다시 급락해 CAPITULATION 복귀, 그동안 가격은 계속 하락
        double[] hash = concat(repeat(100, 60), repeat(50, 20), repeat(200, 8), repeat(1, 60));
        List<DatedHashRibbon> r = CryptoHashRibbons.history(series(START, hash), prices(hash.length, i -> 1000 - i), TODAY, 200);

        assertThat(r.stream().anyMatch(p -> p.state() == DatedHashRibbon.State.RECOVERY && p.date().isAfter(START.plusDays(79)))).isTrue();
        assertThat(signals(r)).isEmpty();
    }

    @Test
    void 가격_데이터가_없거나_20점_미만이면_buySignal_없음() {
        assertThat(signals(CryptoHashRibbons.history(series(START, RECOVERING), List.of(), TODAY, 200))).isEmpty();
        assertThat(signals(CryptoHashRibbons.history(series(START, RECOVERING), prices(19, i -> 100 + i), TODAY, 200))).isEmpty();
    }

    @Test
    void 오늘_이후_점은_제외() {
        List<DatedValue> in = series(TODAY.minusDays(3), 1, 1, 1, 1, 1); // today-3 .. today+1

        assertThat(CryptoHashRibbons.history(in, List.of(), TODAY, 10).getLast().date()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void days만큼_뒤에서_자르되_SMA는_전체로_계산() {
        List<DatedHashRibbon> r = CryptoHashRibbons.history(series(START, repeat(100, 120)), List.of(), TODAY, 10);

        assertThat(r).hasSize(10);
        assertThat(r.getFirst().sma60()).isEqualByComparingTo("100.00");
    }

    @Test
    void 완성_점이_없으면_예외() {
        assertThatThrownBy(() -> CryptoHashRibbons.history(List.of(), List.of(), TODAY, 10)).isInstanceOf(IllegalStateException.class);
    }
}
