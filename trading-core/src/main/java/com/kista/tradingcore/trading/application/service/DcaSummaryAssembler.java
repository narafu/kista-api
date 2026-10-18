package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

// DCA 조회 요약 조립 — 다음 실행일은 US 세션일로 계산해 KST 거래일로 되돌린다
@Component
@RequiredArgsConstructor
class DcaSummaryAssembler {

    private final MarketCalendarPort marketCalendarPort;

    // fromKstTradeDate(포함)부터 첫 실행 예정일 — 코인은 24/7 KST 단일 기준(휴장·US 세션 변환 없음)
    DcaSummary assemble(DcaConfig config, DcaExecution last, LocalDate fromKstTradeDate, Market market) {
        if (market == Market.CRYPTO_KRW) {
            return DcaSchedule.nextExecutionDay(fromKstTradeDate, config, d -> true)
                    .map(kst -> new DcaSummary(config, kst, kst, last))
                    .orElseGet(() -> new DcaSummary(config, null, null, last));
        }
        LocalDate fromUs = marketCalendarPort.usSessionDateOf(fromKstTradeDate);
        return DcaSchedule.nextExecutionDay(fromUs, config, marketCalendarPort::isUsSessionOpen)
                .map(us -> new DcaSummary(config, us, marketCalendarPort.kstTradeDateOf(us), last))
                .orElseGet(() -> new DcaSummary(config, null, null, last));
    }

    // 배치가 판정 가능한 첫 KST 거래일
    // 주식: TradingService.filterScheduledStart가 시작일 당일을 제외(tradeDate.isAfter(startDate)) — 오늘 회차 판정이 끝났으면 다음 날부터
    // 코인: 시작일 제외 없음 — 오늘 실행 시각이 지났거나 오늘 이미 판정했으면 내일부터
    static LocalDate firstEligibleDate(LocalDate cycleStartDate, DcaExecution last, DcaConfig config, Market market, LocalDateTime nowKst) {
        if (market == Market.CRYPTO_KRW) {
            LocalDate today = nowKst.toLocalDate();
            boolean hourPassed = nowKst.getHour() > config.executionHour();
            boolean decidedToday = last != null && last.tradeDate().equals(today);
            return hourPassed || decidedToday ? today.plusDays(1) : today;
        }
        LocalDate from = DstInfo.nextTradeDate();
        if (last != null && last.tradeDate().equals(from)) from = from.plusDays(1);
        LocalDate afterStart = cycleStartDate.plusDays(1);
        return from.isBefore(afterStart) ? afterStart : from;
    }
}
