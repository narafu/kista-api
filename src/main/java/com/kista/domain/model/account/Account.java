package com.kista.domain.model.account;

import com.kista.domain.model.strategy.Ticker;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public record Account(
        UUID id,                    // PK
        UUID userId,                // FK → users.id
        String nickname,            // 계좌 별칭
        String accountNo,           // 계좌번호 (복호화된 값)
        String kisAppKey,           // KIS App Key (복호화된 값)
        String kisSecretKey,        // KIS Secret Key (복호화된 값)
        String kisAccountType,      // 계좌 상품 코드 (기본: 01)
        StrategyType strategyType,  // 매매 전략
        StrategyStatus strategyStatus, // 전략 실행 상태
        Ticker ticker,              // 거래 종목 (exchangeCode 포함)
        BigDecimal multiple,        // 배수 (기본값 1.0)
        Broker broker,              // 증권사 (기본: KIS)
        Instant createdAt,
        Instant updatedAt
) {
    public enum Broker { KIS, TOSS }

    @Getter
    @RequiredArgsConstructor
    public enum StrategyType {
        // Ticker Enum을 직접 활용하여 관계 정의
        INFINITE(EnumSet.of(Ticker.SOXL)),
        PRIVACY(EnumSet.of(Ticker.TQQQ, Ticker.USD, Ticker.SOXL));

        // 해당 전략에서 사용 가능한 티커 집합
        private final Set<Ticker> availableTickers;

        /**
         * 이 전략에서 해당 Ticker가 사용 가능한지 검증
         */
        public boolean isSupported(Ticker ticker) {
            if (ticker == null) return false;
            return this.availableTickers.contains(ticker);
        }
    }

    public enum StrategyStatus {
        ACTIVE, // 매매 스케줄링 실행 중
        PAUSED  // 매매 중지 (스케줄링 제외)
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

        public Instant getRetryAfter() {
            return retryAfter;
        }
    }

    public static class InvalidKisKeyException extends RuntimeException {
        public InvalidKisKeyException() {
            super("KIS API 키가 유효하지 않습니다");
        }
    }
}
