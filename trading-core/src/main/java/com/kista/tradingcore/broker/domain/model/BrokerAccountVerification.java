package com.kista.tradingcore.broker.domain.model;

// 계좌 등록·키 수정 검증 결과 — Mock·Meritz: null, Toss: accountSeq
public record BrokerAccountVerification(
        String brokerAccountCode // 증권사 API 보조 식별자
) {
}
