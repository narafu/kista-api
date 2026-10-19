package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

// 현재 펀딩비율과 다음 정산 시각
public record FundingRateQuote(
        BigDecimal rate, // 현재(마지막) 펀딩비율
        Instant nextFundingTime // 다음 정산 시각
) {}
