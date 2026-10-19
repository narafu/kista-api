package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일자별 김치 프리미엄 두 기준 (%)
public record DatedPremiumPair(
        LocalDate date, // 기준 일자 (KST = UTC 일자)
        BigDecimal official, // 공식 환율 기준 김프 % — 환율이 없는 날은 null
        BigDecimal tether // 테더(Upbit USDT) 기준 김프 %
) {}
