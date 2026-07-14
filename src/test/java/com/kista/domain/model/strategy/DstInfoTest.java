package com.kista.domain.model.strategy;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.kista.domain.model.strategy.DstInfo.MarketSession.BLOCKED;
import static com.kista.domain.model.strategy.DstInfo.MarketSession.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DstInfo 검증")
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

    // ─────────────────────────────────────────────────────────────────────────
    // sessionAt() — DIRECT/BLOCKED 시간대 판단 (package-private, 같은 패키지에서 접근)
    // ─────────────────────────────────────────────────────────────────────────

    // isDst=true  → BLOCKED: [05:00, 17:00), DIRECT: 그 외
    // isDst=false → BLOCKED: [06:00, 18:00), DIRECT: 그 외
    private static final DstInfo DST     = new DstInfo(true,  null, null, null);
    private static final DstInfo NON_DST = new DstInfo(false, null, null, null);

    @Nested
    @DisplayName("DST(서머타임) 세션 판단 — BLOCKED=[05:00,17:00)")
    class DstSessionTest {

        @ParameterizedTest(name = "{0} {1} → DIRECT")
        @CsvSource({
            "MONDAY,   04:59",   // BLOCKED 경계 직전 — DIRECT
            "MONDAY,   17:00",   // BLOCKED 종료 경계(포함 안 됨) — DIRECT
            "MONDAY,   17:01",   // BLOCKED 종료 직후 — DIRECT
            "MONDAY,   23:59",   // 자정 직전 — DIRECT
            "MONDAY,   00:00",   // 자정 — DIRECT
            "FRIDAY,   20:00",   // 평일 저녁 — DIRECT
        })
        void direct_dst(DayOfWeek day, LocalTime time) {
            assertThat(DST.sessionAt(day, time)).isEqualTo(DIRECT);
        }

        @ParameterizedTest(name = "{0} {1} → BLOCKED")
        @CsvSource({
            "MONDAY,   05:00",   // BLOCKED 시작 경계(포함) — BLOCKED
            "MONDAY,   05:01",   // BLOCKED 내부 — BLOCKED
            "MONDAY,   12:00",   // BLOCKED 대표 내부 값 — BLOCKED
            "MONDAY,   16:59",   // BLOCKED 종료 경계 직전 — BLOCKED
            "SATURDAY, 20:00",   // 주말(토) 항상 BLOCKED
            "SUNDAY,   10:00",   // 주말(일) 항상 BLOCKED
        })
        void blocked_dst(DayOfWeek day, LocalTime time) {
            assertThat(DST.sessionAt(day, time)).isEqualTo(BLOCKED);
        }
    }

    @Nested
    @DisplayName("비DST(동절기) 세션 판단 — BLOCKED=[06:00,18:00)")
    class NonDstSessionTest {

        @ParameterizedTest(name = "{0} {1} → DIRECT")
        @CsvSource({
            "TUESDAY,  05:59",   // BLOCKED 경계 직전 — DIRECT
            "TUESDAY,  18:00",   // BLOCKED 종료 경계(포함 안 됨) — DIRECT
            "TUESDAY,  18:01",   // BLOCKED 종료 직후 — DIRECT
            "TUESDAY,  23:59",   // 자정 직전 — DIRECT
            "TUESDAY,  00:00",   // 자정 — DIRECT
            "THURSDAY, 21:00",   // 평일 저녁 — DIRECT
        })
        void direct_nondst(DayOfWeek day, LocalTime time) {
            assertThat(NON_DST.sessionAt(day, time)).isEqualTo(DIRECT);
        }

        @ParameterizedTest(name = "{0} {1} → BLOCKED")
        @CsvSource({
            "TUESDAY,  06:00",   // BLOCKED 시작 경계(포함) — BLOCKED
            "TUESDAY,  06:01",   // BLOCKED 내부 — BLOCKED
            "TUESDAY,  12:00",   // BLOCKED 대표 내부 값 — BLOCKED
            "TUESDAY,  17:59",   // BLOCKED 종료 경계 직전 — BLOCKED
            "SATURDAY, 20:00",   // 주말(토) 항상 BLOCKED
            "SUNDAY,   10:00",   // 주말(일) 항상 BLOCKED
        })
        void blocked_nondst(DayOfWeek day, LocalTime time) {
            assertThat(NON_DST.sessionAt(day, time)).isEqualTo(BLOCKED);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isRegularSessionActiveAt() — DIRECT(프리마켓+정규장) 중 실제 정규장 진행 여부
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DST(서머타임) 정규장 진행 판단 — 정규장=[22:30,05:00) 래핑")
    class DstRegularSessionTest {

        @ParameterizedTest(name = "{0} {1} → 정규장 진행 중")
        @CsvSource({
            "MONDAY,   22:30",   // 개장 경계(포함)
            "MONDAY,   23:59",   // 자정 직전
            "TUESDAY,  00:00",   // 자정
            "TUESDAY,  04:59",   // 마감 경계 직전
        })
        void active_dst(DayOfWeek day, LocalTime time) {
            assertThat(DST.isRegularSessionActiveAt(day, time)).isTrue();
        }

        @ParameterizedTest(name = "{0} {1} → 정규장 미진행(프리마켓·장마감 후)")
        @CsvSource({
            "MONDAY,   17:00",   // 프리마켓 시작
            "MONDAY,   20:00",   // 프리마켓 — 개장 전이라 최신 캔들은 이미 확정된 직전 종가
            "MONDAY,   22:29",   // 개장 직전
            "TUESDAY,  05:00",   // 마감 경계(포함)
            "TUESDAY,  12:00",   // 장마감 후(BLOCKED)
            "SATURDAY, 20:00",   // 주말
        })
        void inactive_dst(DayOfWeek day, LocalTime time) {
            assertThat(DST.isRegularSessionActiveAt(day, time)).isFalse();
        }
    }

    @Nested
    @DisplayName("비DST(동절기) 정규장 진행 판단 — 정규장=[23:30,06:00) 래핑")
    class NonDstRegularSessionTest {

        @ParameterizedTest(name = "{0} {1} → 정규장 진행 중")
        @CsvSource({
            "MONDAY,   23:30",   // 개장 경계(포함)
            "MONDAY,   23:59",   // 자정 직전
            "TUESDAY,  00:00",   // 자정
            "TUESDAY,  05:59",   // 마감 경계 직전
        })
        void active_nondst(DayOfWeek day, LocalTime time) {
            assertThat(NON_DST.isRegularSessionActiveAt(day, time)).isTrue();
        }

        @ParameterizedTest(name = "{0} {1} → 정규장 미진행(프리마켓·장마감 후)")
        @CsvSource({
            "MONDAY,   18:00",   // 프리마켓 시작
            "MONDAY,   21:00",   // 프리마켓 — 개장 전이라 최신 캔들은 이미 확정된 직전 종가
            "MONDAY,   23:29",   // 개장 직전
            "TUESDAY,  06:00",   // 마감 경계(포함)
            "TUESDAY,  12:00",   // 장마감 후(BLOCKED)
            "SATURDAY, 20:00",   // 주말
        })
        void inactive_nondst(DayOfWeek day, LocalTime time) {
            assertThat(NON_DST.isRegularSessionActiveAt(day, time)).isFalse();
        }
    }
}
