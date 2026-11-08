package com.kista.tradingcore.trading.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyCycleTest {

    // 시작예정일 당일 거래일은 아직 시작 전 — 다음날부터 시작
    @Test
    void hasStartedBy_isTrueOnlyAfterStartDate() {
        LocalDate startDate = LocalDate.of(2026, 1, 6);
        StrategyCycle cycle = StrategyCycle.start(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, startDate);

        assertThat(cycle.hasStartedBy(startDate.minusDays(1))).isFalse();
        assertThat(cycle.hasStartedBy(startDate)).isFalse();
        assertThat(cycle.hasStartedBy(startDate.plusDays(1))).isTrue();
    }
}
