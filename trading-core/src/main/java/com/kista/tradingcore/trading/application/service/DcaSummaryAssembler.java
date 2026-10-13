package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

// DCA 조회 요약 조립 — 다음 실행일은 US 세션일로 계산해 KST 거래일로 되돌린다
@Component
@RequiredArgsConstructor
class DcaSummaryAssembler {

    private final MarketCalendarPort marketCalendarPort;

    // fromKstTradeDate(포함)부터 첫 실행 예정일
    DcaSummary assemble(DcaConfig config, DcaExecution last, LocalDate fromKstTradeDate) {
        LocalDate fromUs = marketCalendarPort.usSessionDateOf(fromKstTradeDate);
        return DcaSchedule.nextExecutionDay(fromUs, config, marketCalendarPort::isUsSessionOpen)
                .map(us -> new DcaSummary(config, us, marketCalendarPort.kstTradeDateOf(us), last))
                .orElseGet(() -> new DcaSummary(config, null, null, last));
    }

    // 배치가 판정 가능한 첫 KST 거래일 — TradingService.filterScheduledStart는 시작일 당일을 제외(tradeDate.isAfter(startDate))
    // 오늘 회차 판정이 이미 끝났으면 다음 날부터
    static LocalDate firstEligibleDate(LocalDate cycleStartDate, DcaExecution last) {
        LocalDate from = DstInfo.nextTradeDate();
        if (last != null && last.tradeDate().equals(from)) from = from.plusDays(1);
        LocalDate afterStart = cycleStartDate.plusDays(1);
        return from.isBefore(afterStart) ? afterStart : from;
    }
}
