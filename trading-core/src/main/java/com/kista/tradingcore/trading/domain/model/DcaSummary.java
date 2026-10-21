package com.kista.tradingcore.trading.domain.model;

import com.kista.tradingcore.matching.domain.model.DcaRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// DCA 전략 조회 요약 — 규칙별 설정·다음 판정 예정일(보정 반영)·최근 회차 + 원장 보유
public record DcaSummary(
        List<RuleSummary> rules,        // 활성 버전 규칙 (입력 순서)
        BigDecimal currentHoldings,     // 원장(최신 cycle_position) 보유 수량 — 코인 소수 포함, 원장 없으면 null
        BigDecimal avgPrice             // 원장 평단 (보유 없으면 null)
) {
    public record RuleSummary(
            DcaRule rule,                  // 규칙 설정 (ruleKey 확정)
            LocalDate nextUsSessionDate,   // 다음 판정 예정 US 세션일 (캘린더 미존재 시 null, 코인은 KST 일자)
            LocalDate nextKstTradeDate,    // 위와 짝인 KST 거래일 (null 동반)
            DcaExecution lastExecution     // 이 규칙의 최근 dca_execution (없으면 null)
    ) {}
}
