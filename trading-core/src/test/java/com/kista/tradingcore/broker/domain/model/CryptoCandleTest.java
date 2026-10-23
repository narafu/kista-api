package com.kista.tradingcore.broker.domain.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoCandleTest {

    @Test
    void latestCompletedCandleBoundaryIsKst0900() {
        assertThat(CryptoCandle.latestCompletedKstDate(LocalDateTime.of(2026, 10, 23, 8, 59))).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(CryptoCandle.latestCompletedKstDate(LocalDateTime.of(2026, 10, 23, 9, 0))).isEqualTo(LocalDate.of(2026, 10, 22));
    }
}
