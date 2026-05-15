package com.kista.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,                    // PK
        UUID userId,                // FK → users.id
        String nickname,            // 계좌 별칭
        String accountNo,           // 계좌번호 (복호화된 값)
        String kisAppKey,           // KIS App Key (복호화된 값)
        String kisSecretKey,        // KIS Secret Key (복호화된 값)
        String kisAccountType,      // 계좌 상품 코드 (기본: 01)
        StrategyType strategyType,          // 매매 전략
        StrategyStatus strategyStatus, // 전략 실행 상태
        String telegramBotToken,    // 계좌별 텔레그램 봇 토큰 (AES-256 암호화 저장, null 가능)
        String telegramChatId,      // 계좌별 텔레그램 Chat ID (null 가능)
        String symbol,              // 거래 종목 코드 (예: SOXL)
        String exchangeCode,        // 해외거래소 코드 (예: AMS)
        Instant createdAt,
        Instant updatedAt
) {}
