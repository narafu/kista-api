package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DcaSummaryAssemblerTest {

    private final MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    private static DcaRule rule(OrderType type, DcaFrequency frequency, java.time.DayOfWeek dow, Integer dom, String amount) {
        return new DcaRule("k", OrderDirection.BUY, frequency, dow, dom, type,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, java.util.List.of());
    }

    private final DcaSummaryAssembler assembler = new DcaSummaryAssembler(calendar);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    @Test
    void next_execution_converts_us_session_back_to_kst() {
        // KST 2026-10-14 <-> US 10-13, 평일 전부 개장
        when(calendar.usSessionDateOf(LocalDate.of(2026, 10, 14))).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenAnswer(inv -> {
            DayOfWeek d = inv.<LocalDate>getArgument(0).getDayOfWeek();
            return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
        });
        when(calendar.kstTradeDateOf(LocalDate.of(2026, 10, 30))).thenReturn(LocalDate.of(2026, 10, 31));
        DcaRule monthlyEnd = rule(OrderType.MOC, DcaFrequency.MONTHLY, null, null, "300");

        DcaSummary.RuleSummary summary = assembler.assembleRule(monthlyEnd, null, LocalDate.of(2026, 10, 14), Currency.USD);

        assertThat(summary.nextUsSessionDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(summary.nextKstTradeDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(summary.lastExecution()).isNull();
    }

    @Test
    void next_execution_null_when_calendar_unknown() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaRule daily = rule(OrderType.MOC, DcaFrequency.DAILY, null, null, "300");

        DcaSummary.RuleSummary summary = assembler.assembleRule(daily, null, LocalDate.of(2026, 10, 14), Currency.USD);

        assertThat(summary.nextUsSessionDate()).isNull();
        assertThat(summary.nextKstTradeDate()).isNull();
    }

    @Test
    void firstEligibleDate_skips_start_day_itself() {
        LocalDate today = DstInfo.nextTradeDate(NOW.atZone(TimeZones.KST));
        assertThat(assembler.firstEligibleDate(today.plusDays(3), null, NOW)).isEqualTo(today.plusDays(4));
        // 시작일이 오늘이면 시작일 당일은 판정 제외 — 다음 날부터
        assertThat(assembler.firstEligibleDate(today, null, NOW)).isEqualTo(today.plusDays(1));
    }

    @Test
    void firstEligibleDate_moves_to_next_day_when_today_already_decided() {
        LocalDate today = DstInfo.nextTradeDate(NOW.atZone(TimeZones.KST));
        DcaExecution decidedToday = DcaExecution.ordered(UUID.randomUUID(), "k", com.kista.shared.kernel.OrderDirection.BUY, today, UUID.randomUUID(), BigDecimal.valueOf(1), BigDecimal.ONE);
        assertThat(assembler.firstEligibleDate(today.minusDays(10), decidedToday, NOW)).isEqualTo(today.plusDays(1));
    }

    @Test
    void stockFirstEligibleDateUsesEarlyCloseCutoff() {
        LocalDate date = LocalDate.of(2026, 11, 28);
        var close = date.atTime(3, 0).atZone(TimeZones.KST).toInstant();
        when(calendar.findSession(date)).thenReturn(Optional.of(new MarketSessionTimes(close.minusSeconds(12600), close)));
        assertThat(assembler.firstEligibleDate(date.minusDays(10), null, date.atTime(2, 29))).isEqualTo(date);
        assertThat(assembler.firstEligibleDate(date.minusDays(10), null, date.atTime(2, 30))).isEqualTo(date.plusDays(1));
    }

    @Test
    void holdingPercentRule_keepsPercentUntouched() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaRule rule = new DcaRule("k", OrderDirection.SELL, DcaFrequency.DAILY, null, null, OrderType.MOC,
                new DcaSizing(DcaSizingType.HOLDING_PERCENT, null, new BigDecimal("50.00")), DcaShortfallPolicy.PARTIAL, java.util.List.of());

        DcaSummary.RuleSummary s = assembler.assembleRule(rule, null, LocalDate.of(2026, 10, 14), Currency.USD);

        assertThat(s.rule().sizing().amount()).isNull();
        assertThat(s.rule().sizing().percent()).hasToString("50.00");
    }

    @Test
    void usdRule_keepsScale2() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaRule daily = rule(OrderType.MOC, DcaFrequency.DAILY, null, null, "100.00");

        DcaSummary.RuleSummary s = assembler.assembleRule(daily, null, LocalDate.of(2026, 10, 14), Currency.USD);

        assertThat(s.rule().sizing().amount()).hasToString("100.00");
    }
}
