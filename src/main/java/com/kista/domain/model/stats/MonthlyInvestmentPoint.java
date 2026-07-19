package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

// 월말 USD 투자지수와 해당 월의 현금흐름 조정 수익률
public record MonthlyInvestmentPoint(
        LocalDate baseMonth,              // 기준 월의 첫날
        BigDecimal investmentIndexUsd,    // 월말 USD 누적 투자지수
        BigDecimal monthlyReturn          // 외부 현금흐름을 제외한 월 수익률
) {}
