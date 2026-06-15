package com.kista.domain.model.strategy;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DstInfo.calculate() DST 판단 검증")
class DstInfoTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Test
    @DisplayName("EDT(서머타임): orderAt=04:30 KST, postClose=05:10 KST, marketOpen=22:30 KST")
    void summer_dst() {
        // 2024-06-15 04:00 KST = 2024-06-14 19:00 UTC → NY=EDT(UTC-4) → isDst=true
        ZonedDateTime summer = ZonedDateTime.of(2024, 6, 15, 4, 0, 0, 0, KST);

        DstInfo info = DstInfo.calculate(summer);

        assertThat(info.isDst()).isTrue();
        ZonedDateTime deadline = info.orderAt().atZone(KST);
        assertThat(deadline.toLocalDate()).isEqualTo(LocalDate.of(2024, 6, 15));
        assertThat(deadline.toLocalTime()).isEqualTo(LocalTime.of(4, 30));
        ZonedDateTime post = info.postClose().atZone(KST);
        assertThat(post.toLocalTime()).isEqualTo(LocalTime.of(5, 10));
        // 개장 시각: NY 09:30 EDT = KST 22:30
        ZonedDateTime open = info.marketOpen().atZone(KST);
        assertThat(open.toLocalDate()).isEqualTo(LocalDate.of(2024, 6, 15));
        assertThat(open.toLocalTime()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    @DisplayName("EST(동절기): orderAt=05:30 KST, postClose=06:10 KST, marketOpen=23:30 KST")
    void winter_nondst() {
        // 2024-01-15 04:00 KST = 2024-01-14 19:00 UTC → NY=EST(UTC-5) → isDst=false
        ZonedDateTime winter = ZonedDateTime.of(2024, 1, 15, 4, 0, 0, 0, KST);

        DstInfo info = DstInfo.calculate(winter);

        assertThat(info.isDst()).isFalse();
        ZonedDateTime deadline = info.orderAt().atZone(KST);
        assertThat(deadline.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(deadline.toLocalTime()).isEqualTo(LocalTime.of(5, 30));
        ZonedDateTime post = info.postClose().atZone(KST);
        assertThat(post.toLocalTime()).isEqualTo(LocalTime.of(6, 10));
        // 개장 시각: NY 09:30 EST = KST 23:30
        ZonedDateTime open = info.marketOpen().atZone(KST);
        assertThat(open.toLocalDate()).isEqualTo(LocalDate.of(2024, 1, 15));
        assertThat(open.toLocalTime()).isEqualTo(LocalTime.of(23, 30));
    }
}
