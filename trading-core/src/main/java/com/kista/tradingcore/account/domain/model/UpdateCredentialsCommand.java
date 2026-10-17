package com.kista.tradingcore.account.domain.model;

// 계좌 API 키 교체 입력 — 평문(저장 시 persistence 경계에서 암호화)
public record UpdateCredentialsCommand(
        String appKey,    // 새 앱 키 (업비트 Access Key)
        String secretKey  // 새 앱 시크릿 (업비트 Secret Key)
) {}
