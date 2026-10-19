package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 날짜별 단일 값 시계열 점 (일봉 종가, OI 명목가 등)
public record DatedValue(
        LocalDate date, // 기준 일자
        BigDecimal value // 해당 일자 값
) {}
