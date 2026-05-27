package com.kista.domain.model.account;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

public record Account(
        UUID id,               // PK
        UUID userId,           // FK → users.id
        String nickname,       // 계좌 별칭
        String accountNo,      // 계좌번호 (복호화된 값)
        String kisAppKey,      // KIS App Key (복호화된 값)
        String kisSecretKey,   // KIS Secret Key (복호화된 값)
        String kisAccountType, // 계좌 상품 코드 (기본: 01)
        Broker broker          // 증권사 (기본: KIS)
) {
    @Getter
    @RequiredArgsConstructor
    public enum Broker {
        KIS("한국투자증권"),  // 한국투자증권 Open API
        TOSS("토스증권");     // 토스증권 Open API

        private final String label; // 한국어 표시 이름
    }

    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    public static class CooldownException extends RuntimeException {
        private final Instant retryAfter; // 재신청 가능 시각

        public CooldownException(Instant retryAfter) {
            super("재신청 대기 중입니다. 가능 시각: " + retryAfter);
            this.retryAfter = retryAfter;
        }

        public Instant getRetryAfter() { return retryAfter; }
    }

    public static class InvalidKisKeyException extends RuntimeException {
        public InvalidKisKeyException() { super("KIS API 키가 유효하지 않습니다"); }
    }
}
