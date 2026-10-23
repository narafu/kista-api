package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

// DCA 조회 요약 조립 — 규칙별 다음 실행일은 US 세션일로 계산해 KST 거래일로 되돌린다
@Component
@RequiredArgsConstructor
class DcaSummaryAssembler {

    private final MarketCalendarPort marketCalendarPort;

    // 전략 요약 — 규칙마다 배치가 판정 가능한 첫 거래일부터 다음 실행 예정일 계산. lastByRule: ruleKey → 최근 회차
    DcaSummary assemble(DcaStrategyConfig config, Map<String, DcaExecution> lastByRule, LocalDate cycleStartDate,
                        Market market, LocalDateTime nowKst, BigDecimal currentHoldings, BigDecimal avgPrice) {
        return new DcaSummary(config.rules().stream().map(rule -> {
            DcaExecution last = lastByRule.get(rule.ruleKey());
            return assembleRule(rule, last, firstEligibleDate(cycleStartDate, last, rule, market, nowKst), market);
        }).toList(), currentHoldings, avgPrice);
    }

    // fromKstTradeDate(포함)부터 첫 실행 예정일 — 코인은 24/7 KST 단일 기준(휴장·US 세션 변환 없음)
    DcaSummary.RuleSummary assembleRule(DcaRule rule, DcaExecution last, LocalDate fromKstTradeDate, Market market) {
        if (market.isCrypto()) {
            return DcaSchedule.nextExecutionDay(fromKstTradeDate, rule, d -> true)
                    .map(kst -> new DcaSummary.RuleSummary(rule, kst, kst, last))
                    .orElseGet(() -> new DcaSummary.RuleSummary(rule, null, null, last));
        }
        LocalDate fromUs = marketCalendarPort.usSessionDateOf(fromKstTradeDate);
        return DcaSchedule.nextExecutionDay(fromUs, rule, marketCalendarPort::isUsSessionOpen)
                .map(us -> new DcaSummary.RuleSummary(rule, us, marketCalendarPort.kstTradeDateOf(us), last))
                .orElseGet(() -> new DcaSummary.RuleSummary(rule, null, null, last));
    }

    // 배치가 판정 가능한 첫 KST 거래일 (규칙 단위)
    // 주식: TradingService.filterScheduledStart가 시작일 당일을 제외(tradeDate.isAfter(startDate)) — 오늘 회차 판정이 끝났으면 다음 날부터
    // 코인: 시작일 제외 없음 — 실행 시각대의 마지막 틱(HH:50)까지는 판정 전이면 남은 틱이 오늘 실행하므로 오늘, 그 뒤이거나 오늘 이미 판정했으면 내일부터
    static LocalDate firstEligibleDate(LocalDate cycleStartDate, DcaExecution last, DcaRule rule, Market market, LocalDateTime nowKst) {
        if (market.isCrypto()) {
            LocalDate today = nowKst.toLocalDate();
            int hour = nowKst.getHour();
            boolean hourPassed = hour > rule.executionHour()
                    || hour == rule.executionHour() && nowKst.getMinute() > CryptoDcaRoundExecutor.LAST_TICK_MINUTE;
            boolean decidedToday = last != null && last.tradeDate().equals(today);
            return hourPassed || decidedToday ? today.plusDays(1) : today;
        }
        LocalDate from = DstInfo.nextTradeDate();
        if (last != null && last.tradeDate().equals(from)) from = from.plusDays(1);
        LocalDate afterStart = cycleStartDate.plusDays(1);
        return from.isBefore(afterStart) ? afterStart : from;
    }
}
