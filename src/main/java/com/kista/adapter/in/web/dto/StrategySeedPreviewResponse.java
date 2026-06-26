package com.kista.adapter.in.web.dto;

import java.math.BigDecimal;

// 전략 등록/수정 폼의 최소시드·기준가 미리보기 응답
// skipReason: 계산 불가 사유 (예: "NO_PRIVACY_BASE" — 기준매매표 미수신). 정상이면 null
public record StrategySeedPreviewResponse(
        String ticker,
        BigDecimal basePrice,
        BigDecimal minSeed,
        String skipReason
) {}
