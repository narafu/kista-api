package com.kista.api.admin.adapter.in.web.dto;

import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacySeriesFlagsTest {

    private static PrivacyTradeBaseResponse base(String ticker, String date, String start, String pnl, String avg, int holdings) {
        return new PrivacyTradeBaseResponse(UUID.randomUUID(), LocalDate.parse(date), ticker, new BigDecimal(start),
                new BigDecimal(pnl), avg == null ? null : new BigDecimal(avg), holdings, List.of());
    }

    @Test
    void 연초_재설정은_그_해_첫_경계이면서_시작금액_1만달러일때만() {
        var a = base("SOXL", "2025-12-30", "12000", "0", null, 0);
        var b = base("SOXL", "2026-01-05", "10000", "0", null, 0); // 해 첫 경계 + 1만 → 재설정
        var c = base("SOXL", "2026-02-02", "10500", "0", null, 0); // 경계지만 재설정 아님
        var d = base("SOXL", "2026-03-02", "10000", "0", null, 0); // 같은 해 두 번째 경계 → 아님
        var e = base("SOXL", "2026-03-03", "10000", "0", null, 0); // 경계 없음
        var flags = PrivacySeriesFlags.of(List.of(d, a, c, b, e)); // 입력 순서 무관

        assertThat(flags.get(a.id())).isEqualTo(new PrivacySeriesFlags(false, false));
        assertThat(flags.get(b.id())).isEqualTo(new PrivacySeriesFlags(true, true));
        assertThat(flags.get(c.id())).isEqualTo(new PrivacySeriesFlags(true, false));
        assertThat(flags.get(d.id())).isEqualTo(new PrivacySeriesFlags(true, false));
        assertThat(flags.get(e.id())).isEqualTo(new PrivacySeriesFlags(false, false));
    }

    @Test
    void 종목별로_독립_판정() {
        var s1 = base("SOXL", "2026-01-02", "5000", "0", null, 0);
        var t1 = base("TQQQ", "2026-01-02", "10000", "0", null, 0);
        var flags = PrivacySeriesFlags.of(List.of(s1, t1));

        assertThat(flags.get(s1.id()).cycleBoundary()).isFalse();
        assertThat(flags.get(t1.id()).cycleBoundary()).isFalse();
    }

    @Test
    void 추정현금은_시작금액_더하기_실현손익_빼기_평단가x보유수량() {
        assertThat(PrivacySeriesFlags.cash(base("SOXL", "2026-01-02", "10000", "150.5", "20", 100)))
                .isEqualByComparingTo("8150.50");
        assertThat(PrivacySeriesFlags.cash(base("SOXL", "2026-01-02", "10000", "150.5", null, 0)))
                .isEqualByComparingTo("10150.50");
        assertThat(PrivacySeriesFlags.cash(base("SOXL", "2026-01-02", "10000", "0", null, 10))).isNull(); // 보유 중 평단가 없음
    }
}
