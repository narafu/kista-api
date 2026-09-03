package com.kista.market.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.kista.market.domain.model.MarketSessionSnapshot.MarketSession.BLOCKED;
import static com.kista.market.domain.model.MarketSessionSnapshot.MarketSession.DIRECT;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarketSessionSnapshot 검증")
class MarketSessionSnapshotTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Nested
    @DisplayName("평일 DST(서머타임) — BLOCKED=[05:00,17:00)")
    class WeekdayDstTest {

        @ParameterizedTest(name = "{0} → DIRECT (isDst=true)")
        @CsvSource({
            "2024-06-17T04:59:00",   // 월요일, BLOCKED 경계 직전
            "2024-06-17T17:00:00",   // 월요일, BLOCKED 종료 경계(포함 안 됨)
        })
        void direct_dst(String isoLocalDateTime) {
            ZonedDateTime now = ZonedDateTime.parse(isoLocalDateTime + "+09:00[Asia/Seoul]");

            MarketSessionSnapshot snapshot = MarketSessionSnapshot.at(now);

            assertThat(snapshot.isDst()).isTrue();
            assertThat(snapshot.session()).isEqualTo(DIRECT);
        }

        @Test
        @DisplayName("평일 DST — 장마감 직후(05:00)는 BLOCKED")
        void blocked_dst_rightAfterMarketClose() {
            // 2024-06-17(월) 05:00 KST → NY DST 적용 구간(6월) → isDst=true, marketClose 경계(포함)
            ZonedDateTime now = ZonedDateTime.of(2024, 6, 17, 5, 0, 0, 0, KST);

            MarketSessionSnapshot snapshot = MarketSessionSnapshot.at(now);

            assertThat(snapshot.isDst()).isTrue();
            assertThat(snapshot.session()).isEqualTo(BLOCKED);
        }
    }

    @Nested
    @DisplayName("평일 비DST(동절기) 경계 — BLOCKED=[06:00,18:00)")
    class WeekdayNonDstTest {

        @Test
        @DisplayName("비DST — BLOCKED 시작 경계(06:00, 포함)는 BLOCKED")
        void blocked_nonDst_atBoundary() {
            // 2024-01-16(화) 06:00 KST → NY 동절기(1월) → isDst=false
            ZonedDateTime now = ZonedDateTime.of(2024, 1, 16, 6, 0, 0, 0, KST);

            MarketSessionSnapshot snapshot = MarketSessionSnapshot.at(now);

            assertThat(snapshot.isDst()).isFalse();
            assertThat(snapshot.session()).isEqualTo(BLOCKED);
        }

        @Test
        @DisplayName("비DST — BLOCKED 시작 경계 직전(05:59)은 DIRECT")
        void direct_nonDst_justBeforeBoundary() {
            ZonedDateTime now = ZonedDateTime.of(2024, 1, 16, 5, 59, 0, 0, KST);

            MarketSessionSnapshot snapshot = MarketSessionSnapshot.at(now);

            assertThat(snapshot.isDst()).isFalse();
            assertThat(snapshot.session()).isEqualTo(DIRECT);
        }
    }

    @Nested
    @DisplayName("주말 — 요일 무관 항상 BLOCKED")
    class WeekendTest {

        @ParameterizedTest(name = "{0} {1} → BLOCKED")
        @CsvSource({
            "2024-06-15, 20:00",   // 토요일, DST 기간 DIRECT 시간대여도 주말이면 BLOCKED
            "2024-06-16, 10:00",   // 일요일
            "2024-01-13, 20:00",   // 토요일(비DST 기간)
        })
        void blocked_onWeekend(String date, String time) {
            ZonedDateTime now = ZonedDateTime.parse(date + "T" + time + ":00+09:00[Asia/Seoul]");

            MarketSessionSnapshot snapshot = MarketSessionSnapshot.at(now);

            assertThat(snapshot.session()).isEqualTo(BLOCKED);
        }
    }
}
