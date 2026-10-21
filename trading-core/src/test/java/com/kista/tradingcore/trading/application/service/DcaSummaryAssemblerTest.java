package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DcaSummaryAssemblerTest {

    private final MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    private static DcaRule rule(OrderType type, DcaFrequency frequency, java.time.DayOfWeek dow, Integer dom, Integer hour, String amount) {
        return new DcaRule("k", OrderDirection.BUY, frequency, dow, dom, type, hour,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, java.util.List.of());
    }

    private final DcaSummaryAssembler assembler = new DcaSummaryAssembler(calendar);

    private static final DcaRule STOCK = rule(OrderType.MOC, DcaFrequency.DAILY, null, null, null, "300");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final DcaRule CRYPTO_DAILY = rule(OrderType.MARKET, DcaFrequency.DAILY, null, null, 13, "10000");

    @Test
    void cryptoNextExecutionIncludesTodayBeforeHour() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(10, 0))).isEqualTo(today);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(14, 0))).isEqualTo(today.plusDays(1));
        DcaExecution decided = DcaExecution.ordered(UUID.randomUUID(), "k", com.kista.shared.kernel.OrderDirection.BUY, today, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, decided, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(10, 0))).isEqualTo(today.plusDays(1));
    }

    @Test
    void cryptoNextExecutionStaysTodayWithinExecutionHourUntilDecided() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        // 실행 시각대 안에서 판정 전이면 남은 틱(재시도·시각대 중 신규 등록)이 오늘 실행 — 오늘
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 30))).isEqualTo(today);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(12, 59))).isEqualTo(today);
        // 마지막 틱(13:50) 시작 뒤엔 남은 틱이 없어 내일
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 50))).isEqualTo(today);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 55))).isEqualTo(today.plusDays(1));
        // 시각대가 지나면 내일
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(14, 0))).isEqualTo(today.plusDays(1));
    }

    @Test
    void cryptoExactExecutionHourIsTodayUntilDecided() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        // 13:00 정각 판정 전은 오늘, 같은 시각대에 이미 판정했으면 내일
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 0))).isEqualTo(today);
        DcaExecution decided = DcaExecution.ordered(UUID.randomUUID(), "k", com.kista.shared.kernel.OrderDirection.BUY, today, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, decided, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 10))).isEqualTo(today.plusDays(1));
    }

    @Test
    void cryptoWeeklySundayIsNotShiftedByUsHoliday() {
        DcaRule sun = rule(OrderType.MARKET, DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, 13, "10000");
        // 2026-10-14 수 → 다음 일요일 10-18, 캘린더 조회 없음
        DcaSummary.RuleSummary summary = assembler.assembleRule(sun, null, LocalDate.of(2026, 10, 14), Market.CRYPTO_KRW);
        assertThat(summary.nextKstTradeDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        assertThat(summary.nextUsSessionDate()).isEqualTo(LocalDate.of(2026, 10, 18));
    }

    @Test
    void next_execution_converts_us_session_back_to_kst() {
        // KST 2026-10-14 <-> US 10-13, 평일 전부 개장
        when(calendar.usSessionDateOf(LocalDate.of(2026, 10, 14))).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenAnswer(inv -> {
            DayOfWeek d = inv.<LocalDate>getArgument(0).getDayOfWeek();
            return d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
        });
        when(calendar.kstTradeDateOf(LocalDate.of(2026, 10, 30))).thenReturn(LocalDate.of(2026, 10, 31));
        DcaRule monthlyEnd = rule(OrderType.MOC, DcaFrequency.MONTHLY, null, null, null, "300");

        DcaSummary.RuleSummary summary = assembler.assembleRule(monthlyEnd, null, LocalDate.of(2026, 10, 14), Market.US_STOCK);

        assertThat(summary.nextUsSessionDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(summary.nextKstTradeDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(summary.lastExecution()).isNull();
    }

    @Test
    void next_execution_null_when_calendar_unknown() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaRule daily = rule(OrderType.MOC, DcaFrequency.DAILY, null, null, null, "300");

        DcaSummary.RuleSummary summary = assembler.assembleRule(daily, null, LocalDate.of(2026, 10, 14), Market.US_STOCK);

        assertThat(summary.nextUsSessionDate()).isNull();
        assertThat(summary.nextKstTradeDate()).isNull();
    }

    @Test
    void firstEligibleDate_skips_start_day_itself() {
        LocalDate today = DstInfo.nextTradeDate();
        assertThat(DcaSummaryAssembler.firstEligibleDate(today.plusDays(3), null, STOCK, Market.US_STOCK, NOW)).isEqualTo(today.plusDays(4));
        // 시작일이 오늘이면 시작일 당일은 판정 제외 — 다음 날부터
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, STOCK, Market.US_STOCK, NOW)).isEqualTo(today.plusDays(1));
    }

    @Test
    void firstEligibleDate_moves_to_next_day_when_today_already_decided() {
        LocalDate today = DstInfo.nextTradeDate();
        DcaExecution decidedToday = DcaExecution.ordered(UUID.randomUUID(), "k", com.kista.shared.kernel.OrderDirection.BUY, today, UUID.randomUUID(), BigDecimal.valueOf(1), BigDecimal.ONE);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today.minusDays(10), decidedToday, STOCK, Market.US_STOCK, NOW)).isEqualTo(today.plusDays(1));
    }
}
