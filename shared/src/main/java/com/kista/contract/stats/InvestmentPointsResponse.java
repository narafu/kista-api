package com.kista.contract.stats;

import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 투자 성과 시리즈 — GET /api/internal/trading/stats/investment-points 응답 (STRATEGY scope일 때만 selectedStrategy가 채워진다)
public record InvestmentPointsResponse(
        List<InvestmentPointDto> points,    // 시점별 누적 투자지수
        LocalDate effectiveFrom,            // 실제 적용된 조회 시작일
        LocalDate effectiveTo,              // 실제 적용된 조회 종료일
        StrategyRefDto selectedStrategy     // STRATEGY scope의 선택 전략 (PORTFOLIO면 null)
) {
    // 투자 누적지수 시점(월별·주별·일별)과 해당 구간의 현금흐름 조정 수익률
    public record InvestmentPointDto(
            LocalDate baseDate,             // 시점의 기준일
            BigDecimal investmentIndexUsd,  // 시점의 USD 누적 투자지수
            BigDecimal periodReturn         // 외부 현금흐름을 제외한 구간 수익률
    ) {}

    // 전략 최소 투영 — 벤치마크 비교 화면이 쓰는 3필드
    public record StrategyRefDto(
            UUID id,                // 전략 ID
            StrategyType type,      // 전략 종류
            StrategyTicker ticker   // 거래 종목
    ) {}
}
