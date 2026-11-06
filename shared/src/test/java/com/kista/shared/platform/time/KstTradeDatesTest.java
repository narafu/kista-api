package com.kista.shared.platform.time;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class KstTradeDatesTest {

    private static final LocalDate SAT = LocalDate.of(2026, 7, 18);

    @Test
    void _04시29분은_당일() {
        assertThat(KstTradeDates.nextTradeDateAt(SAT, LocalTime.of(4, 29))).isEqualTo(SAT);
    }

    @Test
    void _04시30분은_익일() {
        assertThat(KstTradeDates.nextTradeDateAt(SAT, LocalTime.of(4, 30))).isEqualTo(SAT.plusDays(1));
    }

    @Test
    void 토요일_00시50분은_토요일() {
        assertThat(KstTradeDates.nextTradeDateAt(SAT, LocalTime.of(0, 50))).isEqualTo(SAT);
    }
}
