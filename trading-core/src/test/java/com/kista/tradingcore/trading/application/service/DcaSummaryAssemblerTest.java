package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
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
    private final DcaSummaryAssembler assembler = new DcaSummaryAssembler(calendar);

    private static final DcaConfig STOCK = new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 14, 10, 0);
    private static final DcaConfig CRYPTO_DAILY = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET, DcaFrequency.DAILY, null, null, null, 13);

    @Test
    void cryptoNextExecutionIncludesTodayBeforeHour() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(10, 0))).isEqualTo(today);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(14, 0))).isEqualTo(today.plusDays(1));
        DcaExecution decided = DcaExecution.ordered(UUID.randomUUID(), today, UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ONE);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, decided, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(10, 0))).isEqualTo(today.plusDays(1));
    }

    @Test
    void cryptoNextExecutionMovesToTomorrowFromExecutionHour() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        // 코인 스케줄러는 실행 시각 00분에 돌므로 HH:00 이후엔 오늘 회차가 이미 진행·완료
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 30))).isEqualTo(today.plusDays(1));
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(12, 59))).isEqualTo(today);
    }

    @Test
    void cryptoExactExecutionHourMovesToTomorrow() {
        LocalDate today = LocalDate.of(2026, 10, 14);
        // 13:00 정각은 hour >= executionHour 이므로 이미 실행 시각 — 내일부터
        assertThat(DcaSummaryAssembler.firstEligibleDate(today, null, CRYPTO_DAILY, Market.CRYPTO_KRW, today.atTime(13, 0))).isEqualTo(today.plusDays(1));
    }

    @Test
    void cryptoWeeklySundayIsNotShiftedByUsHoliday() {
        DcaConfig sun = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET, DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, null, 13);
        // 2026-10-14 수 → 다음 일요일 10-18, 캘린더 조회 없음
        DcaSummary summary = assembler.assemble(sun, null, LocalDate.of(2026, 10, 14), Market.CRYPTO_KRW);
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
        DcaConfig monthlyEnd = new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.MONTHLY, null, null);

        DcaSummary summary = assembler.assemble(monthlyEnd, null, LocalDate.of(2026, 10, 14), Market.US_STOCK);

        assertThat(summary.nextUsSessionDate()).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(summary.nextKstTradeDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(summary.lastExecution()).isNull();
    }

    @Test
    void next_execution_null_when_calendar_unknown() {
        when(calendar.usSessionDateOf(any())).thenReturn(LocalDate.of(2026, 10, 13));
        when(calendar.isUsSessionOpen(any())).thenReturn(false);
        DcaConfig daily = new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null);

        DcaSummary summary = assembler.assemble(daily, null, LocalDate.of(2026, 10, 14), Market.US_STOCK);

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
        DcaExecution decidedToday = DcaExecution.ordered(UUID.randomUUID(), today, UUID.randomUUID(), BigDecimal.valueOf(1), BigDecimal.ONE);
        assertThat(DcaSummaryAssembler.firstEligibleDate(today.minusDays(10), decidedToday, STOCK, Market.US_STOCK, NOW)).isEqualTo(today.plusDays(1));
    }
}
