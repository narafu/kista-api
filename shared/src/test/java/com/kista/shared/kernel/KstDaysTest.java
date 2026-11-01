package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class KstDaysTest {

    @Test
    void endExclusive는_다음날_KST_자정을_반환한다() {
        assertThat(KstDays.endExclusive(LocalDate.of(2026, 3, 8))).isEqualTo(Instant.parse("2026-03-08T15:00:00Z"));
    }
}
