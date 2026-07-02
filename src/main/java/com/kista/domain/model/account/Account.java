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
        String appKey,      // API 앱 키 — KIS App Key / Toss Client ID (복호화된 값)
        String secretKey,   // API 앱 시크릿 — KIS Secret Key / Toss Client Secret (복호화된 값)
        String brokerAccountCode, // 증권사 API 보조 식별자 — KIS: null(accountNo에 통합), TOSS: accountSeq
        Broker broker,            // 증권사 (기본: KIS)
        Instant createdAt         // DB created_at, 신규 등록 시 null
) {
    @Getter
    @RequiredArgsConstructor
    public enum Broker {
        TOSS("토스증권",    "토스"),  // 토스증권 Open API
        KIS("한국투자증권", "한투");  // 한국투자증권 Open API

        private final String label;      // 한국어 전체 이름
        private final String shortLabel; // UI 모바일 약칭
    }

    // nickname만 교체 — AccountService.update 전용
    public Account withNickname(String newNickname) {
        return new Account(id, userId, newNickname != null ? newNickname : nickname,
                accountNo, appKey, secretKey, brokerAccountCode, broker, createdAt);
    }

    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("계좌에 대한 접근 권한이 없습니다");
        }
    }

    public static class InvalidKisKeyException extends RuntimeException {
        public InvalidKisKeyException() { super("KIS API 키가 유효하지 않습니다"); }
    }

    // KIS EGW00133 — 1분당 토큰 발급 1회 제한 초과
    public static class KisRateLimitException extends RuntimeException {
        public KisRateLimitException() { super("KIS API 호출 한도를 초과했습니다. 잠시 후 다시 시도하세요"); }
    }

    // 동일 사용자가 같은 계좌번호를 중복 등록 시도한 경우
    public static class DuplicateAccountException extends RuntimeException {
        public DuplicateAccountException(String accountNo) {
            super("이미 등록된 계좌번호입니다: " + accountNo);
        }
    }
}
