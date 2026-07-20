package com.kista.domain.model.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

// 투자 누적지수 시점(월별 또는 일별)과 해당 구간의 현금흐름 조정 수익률
public record InvestmentPoint(
        LocalDate baseDate,               // 시점의 기준일 (월별이면 월의 첫날)
        BigDecimal investmentIndexUsd,     // 시점의 USD 누적 투자지수
        BigDecimal periodReturn           // 외부 현금흐름을 제외한 구간 수익률
) {}
