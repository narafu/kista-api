package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

// 과거 펀딩비 정산 1건
public record FundingRateRecord(
        Instant fundingTime, // 정산 시각
        BigDecimal rate // 정산 펀딩비율 (0.0001 = 0.01%)
) {}
