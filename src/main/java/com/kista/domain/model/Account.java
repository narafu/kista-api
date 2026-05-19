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
        Ticker ticker,              // 거래 종목 (exchangeCode 포함)
        Instant createdAt,
        Instant updatedAt
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }
}
