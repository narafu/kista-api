package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.DcaSummary;

import java.math.BigDecimal;
import java.util.List;

// DCA 조회 응답 블록 — TradingCycleResponse.dca
public record DcaResponse(
        List<DcaRuleResponse> rules,    // 규칙 목록 (입력 순서)
        BigDecimal currentHoldings,     // 원장 보유 수량 (코인 소수 포함, null 가능)
        BigDecimal avgPrice             // 원장 평단 (보유 없으면 null)
) {
    public static DcaResponse from(DcaSummary s) {
        return new DcaResponse(s.rules().stream().map(DcaRuleResponse::from).toList(), s.currentHoldings(), s.avgPrice());
    }
}
