package com.kista.domain.model.tradingcycle;

import com.kista.domain.model.account.Account;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record TradingCycle(
        UUID id,                        // PK
        UUID accountId,                 // FK → accounts.id
        Type type,                      // 매매 전략 종류
        Status status,                  // 전략 실행 상태
        Ticker ticker,                  // 거래 종목 (매매 도메인 메타: 익절률 + 설명)
        BigDecimal initialUsdDeposit,   // 사이클 시작 시 초기 입금액 (PRIVACY: 배수 산출 기준)
        CycleSeedType cycleSeedType     // 사이클 종료 후 자동 재등록 정책
) {
    @Getter
    @RequiredArgsConstructor
    public enum Type {
        INFINITE("20분할 LOC 매매 전략"), // 모든 Ticker 지원
        PRIVACY("Fanding 매매표 기반 전략"); // SOXL 전용

        private final String description; // 전략 설명

        // INFINITE: 전체 Ticker, PRIVACY: SOXL 단일 — Ticker 추가 시 자동 반영
        public Set<Ticker> availableTickers() {
            return switch (this) {
                case PRIVACY -> EnumSet.of(Ticker.SOXL);
                case INFINITE -> EnumSet.allOf(Ticker.class);
            };
        }

        // ticker 결정 규칙: PRIVACY는 SOXL 강제, 그 외는 요청값 우선 → fallback 순
        public Ticker resolveTicker(Ticker requested, Ticker fallback) {
            return switch (this) {
                case PRIVACY -> Ticker.SOXL;
                case INFINITE -> requested != null ? requested : fallback;
            };
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
        TQQQ(new BigDecimal("0.15"), "PROSHARES QQQ 3X"),
        SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X"),
        USD(new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X"),
        MAGX(new BigDecimal("0.20"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X"),
        FNGU(new BigDecimal("0.20"), "MICROSECTORS FANG+ 3X"),
        BULZ(new BigDecimal("0.20"), "MICROSECTORS SOLACTIVE FANG & INNOVATION 3X");

        private final BigDecimal targetProfitRate;       // 익절 목표 수익률 (매매 도메인 정책)
        private final String description;               // 종목 설명 (UI 메타)

        // KIS 응답 String → Ticker 변환. 미등록 종목이면 empty 반환 (필터링 용도)
        public static Optional<Ticker> tryParse(String name) {
            if (name == null) return Optional.empty();
            try {
                return Optional.of(valueOf(name.trim()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum CycleSeedType {
        NONE("연속 안함"),    // 연속 사이클 없음
        MAINTAIN("시드 유지"), // 종료 후 동일 initialUsdDeposit으로 재등록
        MAX("시드 MAX");      // 종료 후 현재 USD 잔고 전액을 initialUsdDeposit으로 재등록

        private final String label; // 한국어 표시 이름

        public boolean isConsecutive() {
            return this != NONE;
        }
    }
}
