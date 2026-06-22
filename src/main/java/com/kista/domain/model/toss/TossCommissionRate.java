package com.kista.domain.model.toss;

import java.math.BigDecimal;
import java.time.LocalDate;

// GET /api/v1/commissions 응답 — 시장별 수수료율
public record TossCommissionRate(
        String marketCountry,    // "KR" | "US"
        BigDecimal rate,         // 수수료율 (%) — 예: 0.015는 0.015%
        LocalDate startDate,     // 적용 시작일 (해외주식은 null)
        LocalDate endDate        // 적용 종료일 (무기한이면 null)
) {}
