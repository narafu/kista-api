package com.kista.domain.model.tradingcycle;

import com.kista.domain.model.account.Account;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record TradingCycle(
        UUID id,                        // PK
        UUID accountId,                 // FK → accounts.id
        Type type,                      // 매매 전략 종류
        Status status,                  // 전략 실행 상태
        Ticker ticker,                  // 거래 종목 (exchangeCode 포함)
        BigDecimal multiple,            // 배수 (기본값 1.0)
        BigDecimal initialUsdDeposit,   // 사이클 시작 시 초기 입금액 (메타 기록용)
        Instant createdAt,
        Instant updatedAt
) {
    @Getter
    @RequiredArgsConstructor
    public enum Type {
        INFINITE(                                                // TQQQ/SOXL/USD 모두 지원
            EnumSet.of(Ticker.TQQQ, Ticker.SOXL, Ticker.USD),
            "무한매수",
            "20분할 LOC 매매 전략",
            Ticker.TQQQ,
            new BigDecimal("1.0")
        ),
        PRIVACY(                                                 // SOXL 전용 (서버 강제)
            EnumSet.of(Ticker.SOXL),
            "PRIVACY",
            "기준 매매표 기반 전략 (SOXL 전용)",
            Ticker.SOXL,
            new BigDecimal("1.0")
        );

        private final Set<Ticker> availableTickers; // 사용 가능한 티커 집합
        private final String label;                 // 한국어 표시 이름
        private final String description;           // 전략 설명
        private final Ticker defaultTicker;         // 기본 선택 티커
        private final BigDecimal defaultMultiple;   // 기본 배수

        public boolean isSupported(Ticker ticker) {
            if (ticker == null) return false;
            return availableTickers.contains(ticker);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        ACTIVE("활성"),  // 매매 스케줄링 실행 중
        PAUSED("중지");  // 매매 중지 (스케줄링 제외)

        private final String label; // 한국어 표시 이름
    }

    @Getter
    @RequiredArgsConstructor
    public enum Ticker {
        TQQQ("NASD", new BigDecimal("0.15"), "TQQQ", "ProShares UltraPro QQQ (3x NASDAQ-100)"), // NASDAQ
        SOXL("AMS",  new BigDecimal("0.20"), "SOXL", "Direxion Daily Semiconductors Bull 3x"),    // NYSE ARCA
        USD("NASD",  new BigDecimal("0.20"), "USD",  "미국달러 (통화 헤지용)");                   // NASDAQ

        private final String exchangeCode;         // KIS OVRS_EXCG_CD
        private final BigDecimal targetProfitRate; // 익절 목표 수익률
        private final String label;                // 표시 이름
        private final String description;          // 종목 설명

        // KIS 응답 String → Ticker 변환. 미등록 종목이면 empty 반환 (필터링 용도)
        public static Optional<Ticker> tryParse(String name) {
            if (name == null) return Optional.empty();
            try { return Optional.of(valueOf(name.trim())); }
            catch (IllegalArgumentException e) { return Optional.empty(); }
        }
    }

    // 소속 계좌 소유권 불일치 시 SecurityException → 컨트롤러 403
    public void verifyOwnedBy(Account account) {
        if (!accountId.equals(account.id())) {
            throw new SecurityException("거래 사이클에 대한 접근 권한이 없습니다");
        }
    }

    // PRIVACY → SOXL 강제, INFINITE → 요청값 (null이면 TQQQ)
    public Ticker resolveDefaultTicker(Ticker requested) {
        return switch (type) {
            case PRIVACY -> Ticker.SOXL;
            case INFINITE -> requested != null ? requested : Ticker.TQQQ;
        };
    }
}
