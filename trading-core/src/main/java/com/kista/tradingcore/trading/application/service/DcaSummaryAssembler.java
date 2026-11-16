package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
import com.kista.tradingcore.matching.domain.strategy.DcaSchedule;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.DcaSummary;
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
            return assembleRule(rule, last, firstEligibleDate(cycleStartDate, last, nowKst), market);
        }).toList(), currentHoldings, avgPrice);
    }

    // fromKstTradeDate(포함)부터 첫 실행 예정일
    DcaSummary.RuleSummary assembleRule(DcaRule source, DcaExecution last, LocalDate fromKstTradeDate, Market market) {
        DcaRule rule = withRoundedAmount(source, market); // 조회 요약 경로 — 계산용 규칙이 아니라 응답에 실리는 사본만 반올림
        LocalDate fromUs = marketCalendarPort.usSessionDateOf(fromKstTradeDate);
        return DcaSchedule.nextExecutionDay(fromUs, rule, marketCalendarPort::isUsSessionOpen)
                .map(us -> new DcaSummary.RuleSummary(rule, us, marketCalendarPort.kstTradeDateOf(us), last))
                .orElseGet(() -> new DcaSummary.RuleSummary(rule, null, null, last));
    }

    // 정액 금액을 시장 견적 통화 자릿수로 반올림 — HOLDING_PERCENT(amount null)는 그대로
    private static DcaRule withRoundedAmount(DcaRule rule, Market market) {
        DcaSizing sizing = rule.sizing();
        if (sizing.amount() == null) return rule;
        return rule.withSizing(new DcaSizing(sizing.type(), market.quoteCurrency().round(sizing.amount()), null));
    }

    // 배치가 판정 가능한 첫 KST 거래일 (규칙 단위)
    // TradingService.filterScheduledStart가 시작일 당일을 제외(tradeDate.isAfter(startDate)) — 오늘 회차 판정이 끝났으면 다음 날부터
    LocalDate firstEligibleDate(LocalDate cycleStartDate, DcaExecution last, LocalDateTime nowKst) {
        LocalDate from = SessionTradeDates.nextTradeDate(nowKst.atZone(TimeZones.KST), marketCalendarPort);
        if (last != null && last.tradeDate().equals(from)) from = from.plusDays(1);
        LocalDate afterStart = cycleStartDate.plusDays(1);
        return from.isBefore(afterStart) ? afterStart : from;
    }
}
