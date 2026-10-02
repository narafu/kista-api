package com.kista.trading.domain.model;

import com.kista.sharedkernel.TimeZones;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DstInfoForOpenBatchTest {

    @Test
    void forOpenBatch_marketOpenIsEveningBeforeTradeDate() {
        // 2026-10-07(수) 거래일 → 개장은 10-06(화) 22:30 KST (10월은 미국 DST)
        DstInfo dst = DstInfo.forOpenBatch(LocalDate.of(2026, 10, 7));

        assertThat(dst.marketOpen()).isEqualTo(ZonedDateTime.of(2026, 10, 6, 22, 30, 0, 0, TimeZones.KST).toInstant());
    }

    @Test
    void marketCloseAt_dst_is0500SameKstDate() {
        DstInfo dst = DstInfo.calculate(ZonedDateTime.of(2026, 10, 7, 4, 40, 0, 0, TimeZones.KST));

        assertThat(dst.marketCloseAt()).isEqualTo(ZonedDateTime.of(2026, 10, 7, 5, 0, 0, 0, TimeZones.KST).toInstant());
    }

    @Test
    void marketCloseAt_nonDst_is0600() {
        DstInfo dst = DstInfo.calculate(ZonedDateTime.of(2026, 12, 2, 5, 0, 0, 0, TimeZones.KST));

        assertThat(dst.marketCloseAt()).isEqualTo(ZonedDateTime.of(2026, 12, 2, 6, 0, 0, 0, TimeZones.KST).toInstant());
    }
}
