package com.kista.tradingcore.broker.domain.model;

import java.time.Instant;

// 계좌 등록·키 수정 검증 결과 — Mock: (null, null), Toss: (accountSeq, null)
public record BrokerAccountVerification(
        String brokerAccountCode, // 증권사 API 보조 식별자
        Instant apiKeyExpiresAt   // 거래소 API 키 만료 시각 (모르면 null)
) {
}
