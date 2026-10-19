package com.kista.tradingcore.stats.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

// 일자별 선물 롱/숏 계좌 비율 (계좌 수 기준)
public record DatedLongShortRatio(
        LocalDate date, // 기준 일자 (바이낸스 UTC 일자)
        BigDecimal longAccount, // 롱 포지션 계좌 비율 (0~1)
        BigDecimal longShortRatio // 롱 계좌 수 / 숏 계좌 수
) {}
