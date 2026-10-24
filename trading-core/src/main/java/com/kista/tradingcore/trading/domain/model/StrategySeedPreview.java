package com.kista.tradingcore.trading.domain.model;

import java.math.BigDecimal;

// 전략 등록/수정 폼의 최소시드·기준가 미리보기 — 도메인 계산 결과
// skipReason: 계산 불가 사유 (예: "NO_PRIVACY_BASE"). 정상이면 null
// availableCash: 증권사 USD 매수가능금액 − 계좌 내 전략별 원장 합 (경고용). MOCK이거나 조회 실패면 null
public record StrategySeedPreview(
        String ticker,
        BigDecimal basePrice,
        BigDecimal minSeed,
        String skipReason,
        BigDecimal availableCash
) {}
