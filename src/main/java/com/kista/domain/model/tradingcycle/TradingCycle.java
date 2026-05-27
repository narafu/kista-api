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
        BigDecimal initialUsdDeposit,   // 사이클 시작 시 초기 입금액 (PRIVACY: 배수 산출 기준)
        Instant createdAt,
        Instant updatedAt
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
    public enum ExchangeCode { // KIS 주문 API 사용 OVRS_EXCG_CD
        NASD("나스닥"),
        NYSE("뉴욕"),
        AMEX("아멕스");

        private final String label; // 한국어 표시 이름
    }

    @Getter
    @RequiredArgsConstructor
    public enum ExcdCode { // KIS 시세 API 사용 EXCD_01
        NAS("나스닥"),
        NYS("뉴욕"),
        AMS("아멕스");

        private final String label; // 한국어 표시 이름
    }

    @Getter
    @RequiredArgsConstructor
    public enum Ticker {
        TQQQ(ExchangeCode.NASD, ExcdCode.NAS, new BigDecimal("0.15"), "PROSHARES QQQ 3X"),
        SOXL(ExchangeCode.AMEX, ExcdCode.AMS, new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X"),
        USD(ExchangeCode.AMEX, ExcdCode.AMS, new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X"),
        MAGX(ExchangeCode.AMEX, ExcdCode.AMS, new BigDecimal("0.20"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X"),
        FNGU(ExchangeCode.AMEX, ExcdCode.AMS, new BigDecimal("0.20"), "MICROSECTORS FANG+ 3X"),
        BULZ(ExchangeCode.AMEX, ExcdCode.AMS, new BigDecimal("0.20"), "MICROSECTORS SOLACTIVE FANG & INNOVATION 3X");

        private final ExchangeCode exchangeCode;        // KIS OVRS_EXCG_CD
        private final ExcdCode excdCode;                // KIS EXCD_01
        private final BigDecimal targetProfitRate;       // 익절 목표 수익률
        private final String description;               // 종목 설명(search_info)

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

}
