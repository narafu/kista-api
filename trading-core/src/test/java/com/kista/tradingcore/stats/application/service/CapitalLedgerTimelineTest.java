package com.kista.tradingcore.stats.application.service;

import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CapitalLedgerTimelineTest {

    private static final UUID CYCLE = UUID.randomUUID();

    private static BigDecimal bd(long v) { return BigDecimal.valueOf(v); }

    private static CyclePosition pos(UUID id, String createdAt, long deposit, long holdings, Long avg) {
        return new CyclePosition(id, CYCLE, bd(deposit), null, avg == null ? null : bd(avg), bd(holdings),
                Instant.parse(createdAt), null);
    }

    private static CycleCapitalEvent ev(UUID posId, String at, long dep, long hold, long principal) {
        return new CycleCapitalEvent(UUID.randomUUID(), CYCLE, posId,
                dep >= 0 ? CycleCapitalEvent.Kind.INJECT : CycleCapitalEvent.Kind.WITHDRAW,
                bd(dep), bd(hold), bd(principal), Instant.parse(at));
    }

    @Test
    void restore_singleInjection_splitsRowAtEventTime() {
        UUID id = UUID.randomUUID();
        var r = pos(id, "2026-06-10T00:00:00Z", 15000, 0, null);
        var out = CapitalLedgerTimeline.restore(List.of(r), List.of(ev(id, "2026-06-20T01:00:00Z", 5000, 0, 5000)));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).createdAt()).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(out.get(0).deposit()).isEqualByComparingTo("10000");
        assertThat(out.get(1).createdAt()).isEqualTo(Instant.parse("2026-06-20T01:00:00Z"));
        assertThat(out.get(1).deposit()).isEqualByComparingTo("15000");
        assertThat(out.get(1).id()).isEqualTo(id);
    }

    @Test
    void restore_twoEventsOnSameRow() {
        UUID id = UUID.randomUUID();
        var r = pos(id, "2026-06-10T00:00:00Z", 14000, 15, 100L);
        var out = CapitalLedgerTimeline.restore(List.of(r), List.of(
                ev(id, "2026-06-21T01:00:00Z", -1000, 0, -1000),
                ev(id, "2026-06-20T01:00:00Z", 5000, 5, 5000)));

        assertThat(out).hasSize(3);
        assertThat(out.get(0).deposit()).isEqualByComparingTo("10000");
        assertThat(out.get(0).holdings()).isEqualByComparingTo("10");
        assertThat(out.get(1).deposit()).isEqualByComparingTo("15000");
        assertThat(out.get(1).holdings()).isEqualByComparingTo("15");
        assertThat(out.get(2).deposit()).isEqualByComparingTo("14000");
        assertThat(out.get(2).holdings()).isEqualByComparingTo("15");
    }

    @Test
    void restore_withdrawal() {
        UUID id = UUID.randomUUID();
        var r = pos(id, "2026-06-10T00:00:00Z", 7000, 0, null);
        var out = CapitalLedgerTimeline.restore(List.of(r), List.of(ev(id, "2026-06-20T01:00:00Z", -3000, 0, -3000)));

        assertThat(out.get(0).deposit()).isEqualByComparingTo("10000");
        assertThat(out.get(1).deposit()).isEqualByComparingTo("7000");
    }

    @Test
    void restore_noEvents_returnsSameOrder() {
        var a = pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 1, 0, null);
        var b = pos(UUID.randomUUID(), "2026-06-11T00:00:00Z", 2, 0, null);
        assertThat(CapitalLedgerTimeline.restore(List.of(a, b), List.of())).containsExactly(a, b);
    }

    @Test
    void restore_eventOnUnknownRow_ignored() {
        var a = pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 1, 0, null);
        assertThat(CapitalLedgerTimeline.restore(List.of(a),
                List.of(ev(UUID.randomUUID(), "2026-06-20T01:00:00Z", 5, 0, 5)))).containsExactly(a);
    }

    @Test
    void principalDeltaAfter_bucketsByKst() {
        var e = ev(UUID.randomUUID(), "2026-06-19T15:30:00Z", 5000, 0, 5000); // KST 06-20 00:30
        assertThat(CapitalLedgerTimeline.principalDeltaAfter(List.of(e), LocalDate.of(2026, 6, 19))).isEqualByComparingTo("5000");
        assertThat(CapitalLedgerTimeline.principalDeltaAfter(List.of(e), LocalDate.of(2026, 6, 20))).isEqualByComparingTo("0");
    }

    @Test
    void bookValue_nullAvgPrice_isDeposit() {
        assertThat(CapitalLedgerTimeline.bookValue(pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 3000, 0, null)))
                .isEqualByComparingTo("3000");
    }

    @Test
    void openBasisRealized_vrRollover_onlySoldPart() {
        var first = pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 5000, 50, 100L);
        var last = pos(UUID.randomUUID(), "2026-06-20T00:00:00Z", 6200, 40, 100L);
        assertThat(CapitalLedgerTimeline.openBasisRealized(bd(10000), List.of(first, last), List.of())).isEqualByComparingTo("200");
    }

    @Test
    void openBasisRealized_sellAllThenRebuy_premiumNotCarried() {
        // 개장: 예수금 100 + 10주(평단 50, 시가 62) = 720, D = 120 → 전량 70 매도 후 3주 70 재매수
        var open = pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 100, 10, 50L);
        var soldOut = pos(UUID.randomUUID(), "2026-06-11T00:00:00Z", 800, 0, null);
        var rebought = pos(UUID.randomUUID(), "2026-06-12T00:00:00Z", 590, 3, 70L);
        // 실현 = 10 × (70 − 62) = 80, 재매수분 원가는 체결가 그대로
        assertThat(CapitalLedgerTimeline.openBasisRealized(bd(720), List.of(open, soldOut, rebought), List.of())).isEqualByComparingTo("80");
    }

    @Test
    void openBasisRealized_noHoldingsAtOpen_premiumDropped() {
        // 보유 0 개장인데 개장 평가(1,000)와 첫 행 예수금(900)이 어긋난 경우 — 차이는 승계 보유분 프리미엄이 아니므로 매수 후 이어지지 않는다
        var open = pos(UUID.randomUUID(), "2026-06-10T00:00:00Z", 900, 0, null);
        var bought = pos(UUID.randomUUID(), "2026-06-11T00:00:00Z", 600, 3, 100L);
        assertThat(CapitalLedgerTimeline.openBasisRealized(bd(1000), List.of(open, bought), List.of())).isEqualByComparingTo("-100");
    }

    @Test
    void openBasisRealized_firstRowMutated_noDoubleCount() {
        UUID f = UUID.randomUUID();
        var first = pos(f, "2026-06-10T00:00:00Z", 15000, 0, null);
        var last = pos(UUID.randomUUID(), "2026-06-20T00:00:00Z", 12600, 30, 100L);
        var events = List.of(ev(f, "2026-06-10T01:00:00Z", 5000, 0, 5000));
        // 개장 평가 10,000(원본 행 장부 10,000) — 이력 5,000 제외
        assertThat(CapitalLedgerTimeline.openBasisRealized(bd(10000), List.of(first, last), events)).isEqualByComparingTo("600");
    }
}
