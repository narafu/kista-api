package com.kista.domain.model.strategy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 계좌별 영속 전략 설정 — 여러 StrategyCycle(매매 라운드)을 거느림
public record Strategy(
        UUID id,                    // PK
        UUID accountId,             // FK → accounts.id
        Type type,                  // 매매 전략 종류
        Status status,              // 전략 실행 상태
        Ticker ticker,              // 거래 종목 (매매 도메인 메타: 익절률 + 설명)
        CycleSeedType cycleSeedType // 사이클 종료 후 자동 재등록 정책
) {
    public static final int DEFAULT_DIVISION_COUNT = 20;

    // 상태만 교체 — 나머지 필드 보존
    public Strategy withStatus(Status newStatus) {
        return new Strategy(id, accountId, type, newStatus, ticker, cycleSeedType);
    }

    // 연속 정책만 교체
    public Strategy withCycleSeedType(CycleSeedType newCycleSeedType) {
        return new Strategy(id, accountId, type, status, ticker, newCycleSeedType);
    }

    public boolean isInfinite() {
        return type == Type.INFINITE;
    }

    public boolean isPrivacy() {
        return type == Type.PRIVACY;
    }

    public boolean isVr() {
        return type == Type.VR;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Type {
        INFINITE("무한매수법"), // 모든 Ticker 지원
        PRIVACY("Fanding P전략"), // SOXL 전용
        VR("밸류리밸런싱"); // TQQQ 전용 — 밸류 기반 리밸런싱

        private final String description; // 전략 설명

        // INFINITE: 전체 Ticker, PRIVACY: SOXL 단일, VR: TQQQ 단일
        public Set<Ticker> availableTickers() {
            return switch (this) {
                case PRIVACY -> EnumSet.of(Ticker.SOXL);
                case VR -> EnumSet.of(Ticker.TQQQ);
                case INFINITE -> EnumSet.allOf(Ticker.class);
            };
        }

        // ticker 결정 규칙: PRIVACY는 SOXL 강제, VR은 TQQQ 강제, 그 외는 요청값 우선 → fallback 순
        public Ticker resolveTicker(Ticker requested, Ticker fallback) {
            return switch (this) {
                case PRIVACY -> Ticker.SOXL;
                case VR -> Ticker.TQQQ;
                case INFINITE -> requested != null ? requested : fallback;
            };
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum Status {
        ACTIVE("운영중"),  // 매매 스케쥴링 실행 중
        PAUSED("일시중지"); // 매매 중지 (스케쥴링 제외)

        private final String label; // 한국어 표시 이름
    }

    @Getter
    @RequiredArgsConstructor
    public enum Ticker {
        MAGX(new BigDecimal("0.15"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X"), // 베타: 2.2~2.4
        USD(new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X"), // 베타: 3.5~3.7
        TQQQ(new BigDecimal("0.15"), "PROSHARES QQQ 3X"), // 베타: 3.4~3.5
        SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X"); // 베타: 5.3~5.5

        private final BigDecimal targetProfitRate;  // 익절 목표 수익률 (매매 도메인 정책)
        private final String description;           // 종목 설명 (UI 메타)

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
        NONE("OFF"),        // holdings 0 → 전략 PAUSE
        MAINTAIN("ON(유지)"), // 종료 후 동일 initialUsdDeposit으로 재등록
        MAX("ON(MAX)");     // 종료 후 마지막 usdDeposit을 initialUsdDeposit으로 재등록

        private final String label; // 한국어 표시 이름

        public boolean isConsecutive() {
            return this != NONE;
        }
    }
}
