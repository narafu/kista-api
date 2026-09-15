package com.kista.admin.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminReorderTimingAvailabilityTest {

    @Test
    void atOpen_atClose_immediate를_그대로_보관한다() {
        AdminReorderTimingAvailability avail = new AdminReorderTimingAvailability(true, true, false);

        assertThat(avail.atOpen()).isTrue();
        assertThat(avail.atClose()).isTrue();
        assertThat(avail.immediate()).isFalse();
    }
}
