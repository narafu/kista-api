package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// 전략 등록/수정 폼의 최소시드·기준가 미리보기 — 도메인 계산 결과
// skipReason: 계산 불가 사유 (예: "NO_PRIVACY_BASE"). 정상이면 null
public record StrategySeedPreview(
        String ticker,
        BigDecimal basePrice,
        BigDecimal minSeed,
        String skipReason
) {}
