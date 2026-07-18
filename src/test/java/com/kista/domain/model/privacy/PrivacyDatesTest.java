package com.kista.domain.model.privacy;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyDatesTest {

    @Test
    void 거래일의_적용_기준표_발행일은_전날() {
        assertThat(PrivacyDates.releaseDateFor(LocalDate.of(2026, 7, 18)))
                .isEqualTo(LocalDate.of(2026, 7, 17));
    }

    @Test
    void 발행일의_적용_거래일은_다음날() {
        assertThat(PrivacyDates.tradeDateOf(LocalDate.of(2026, 7, 17)))
                .isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    void 두_변환은_역함수() {
        LocalDate date = LocalDate.of(2026, 7, 18);
        assertThat(PrivacyDates.tradeDateOf(PrivacyDates.releaseDateFor(date))).isEqualTo(date);
    }
}
