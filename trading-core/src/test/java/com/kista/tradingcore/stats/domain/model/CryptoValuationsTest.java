package com.kista.tradingcore.stats.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoValuationsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    // 어제까지 n일 일봉 — 종가는 1, 2, ..., n (오래된 순)
    private static List<DatedValue> rising(int n) {
        List<DatedValue> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) list.add(new DatedValue(TODAY.minusDays(n - i + 1), BigDecimal.valueOf(i)));
        return list;
    }

    @Test
    void 완성봉이_정확히_1400개면_200WMA를_계산하고_1399개면_null() {
        DatedValuation full = CryptoValuations.history(rising(1400), TODAY, 1).getFirst();
        DatedValuation short1 = CryptoValuations.history(rising(1399), TODAY, 1).getFirst();

        assertThat(full.wma200()).isEqualByComparingTo("700.50"); // (1+...+1400)/1400
        assertThat(full.wma200Multiple()).isEqualByComparingTo("1.9986"); // 1400/700.5
        assertThat(short1.wma200()).isNull();
        assertThat(short1.wma200Multiple()).isNull();
        assertThat(short1.mayerMultiple()).isNotNull();
    }

    @Test
    void Mayer는_200일_이동평균_기준이고_199일이면_null() {
        DatedValuation full = CryptoValuations.history(rising(200), TODAY, 1).getFirst();
        DatedValuation short1 = CryptoValuations.history(rising(199), TODAY, 1).getFirst();

        assertThat(full.mayerMultiple()).isEqualByComparingTo("1.9900"); // 200/100.5
        assertThat(short1.mayerMultiple()).isNull();
    }

    @Test
    void ATH_하락률은_그날까지_최고_종가_대비이고_신고가일은_0() {
        List<DatedValue> closes = List.of(
                new DatedValue(TODAY.minusDays(3), new BigDecimal("100")),
                new DatedValue(TODAY.minusDays(2), new BigDecimal("80")),
                new DatedValue(TODAY.minusDays(1), new BigDecimal("120")));

        List<DatedValuation> h = CryptoValuations.history(closes, TODAY, 3);

        assertThat(h).extracting(DatedValuation::athDrawdownPercent)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0"), new BigDecimal("-20"), new BigDecimal("0"));
    }

    @Test
    void 출력_구간_이전의_고점도_ATH에_반영한다() {
        List<DatedValue> closes = List.of(
                new DatedValue(TODAY.minusDays(2), new BigDecimal("200")),
                new DatedValue(TODAY.minusDays(1), new BigDecimal("150")));

        assertThat(CryptoValuations.history(closes, TODAY, 1).getFirst().athDrawdownPercent()).isEqualByComparingTo("-25");
    }

    @Test
    void 진행_중인_오늘_봉은_제외한다() {
        List<DatedValue> closes = new ArrayList<>(rising(3));
        closes.add(new DatedValue(TODAY, new BigDecimal("999999"))); // ATH를 넘는 진행 중 봉

        List<DatedValuation> h = CryptoValuations.history(closes, TODAY, 10);

        assertThat(h).hasSize(3).noneMatch(v -> v.date().equals(TODAY));
        assertThat(h.getLast().athDrawdownPercent()).isEqualByComparingTo("0");
    }

    @Test
    void 각_일자_값은_그날까지의_데이터만으로_계산한_값과_같다() {
        List<DatedValue> all = new ArrayList<>(rising(1500));
        all.replaceAll(c -> new DatedValue(c.date(), c.value().multiply(BigDecimal.valueOf(c.date().getDayOfMonth() % 7 + 1))));

        List<DatedValuation> full = CryptoValuations.history(all, TODAY, 200);
        for (DatedValuation v : full) {
            DatedValuation truncated = CryptoValuations.history(all, v.date().plusDays(1), 1).getFirst();
            assertThat(v).isEqualTo(truncated);
        }
    }

    @Test
    void 날짜_순서가_섞이거나_중복돼도_날짜_키로_정렬한다() {
        List<DatedValue> closes = List.of(
                new DatedValue(TODAY.minusDays(1), new BigDecimal("50")),
                new DatedValue(TODAY.minusDays(2), new BigDecimal("100")),
                new DatedValue(TODAY.minusDays(1), new BigDecimal("50")));

        List<DatedValuation> h = CryptoValuations.history(closes, TODAY, 10);

        assertThat(h).extracting(DatedValuation::date).containsExactly(TODAY.minusDays(2), TODAY.minusDays(1));
        assertThat(h.getLast().athDrawdownPercent()).isEqualByComparingTo("-50");
    }

    @Test
    void 완성봉이_없으면_실패한다() {
        assertThatThrownBy(() -> CryptoValuations.history(List.of(new DatedValue(TODAY, BigDecimal.ONE)), TODAY, 10))
                .isInstanceOf(IllegalStateException.class);
    }
}
