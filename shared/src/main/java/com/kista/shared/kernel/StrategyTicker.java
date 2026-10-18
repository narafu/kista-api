package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.Optional;

// 거래 종목 — Strategy 도메인 nested enum(Ticker)에서 shared.kernel로 이관(constraints.md "User/Account/Strategy 공유 enum").
// 상수명 byte-identical 유지 필수 — StrategyEntity.ticker @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyTicker {
    MAGX(new BigDecimal("0.15"), "ROUNDHILL DAILY MAGNIFICENT SEVEN 2X", Market.US_STOCK), // 베타: 2.2~2.4
    USD(new BigDecimal("0.20"), "PROSHARES SEMICONDUCTORS 2X", Market.US_STOCK), // 베타: 3.5~3.7
    TQQQ(new BigDecimal("0.15"), "PROSHARES QQQ 3X", Market.US_STOCK), // 베타: 3.4~3.5
    SOXL(new BigDecimal("0.20"), "DIREXION SEMICONDUCTOR DAILY 3X", Market.US_STOCK), // 베타: 5.3~5.5
    UPRO(BigDecimal.ZERO, "PROSHARES ULTRAPRO S&P500 3X", Market.US_STOCK), // DCA 전용 — 익절률 미사용
    QLD(BigDecimal.ZERO, "PROSHARES ULTRA QQQ 2X", Market.US_STOCK),        // DCA 전용 — 익절률 미사용
    BTC(BigDecimal.ZERO, "BITCOIN", Market.CRYPTO_KRW),                     // 코인 DCA 전용 — 업비트 마켓 코드는 어댑터 레지스트리 소유
    ETH(BigDecimal.ZERO, "ETHEREUM", Market.CRYPTO_KRW);                    // 코인 DCA 전용

    private final BigDecimal targetProfitRate;  // 익절 목표 수익률 (매매 도메인 정책)
    private final String description;           // 종목 설명 (UI 메타)
    private final Market market;                // 거래 시장 — 계좌 증권사 시장과 일치해야 한다

    // KIS 응답 String → StrategyTicker 변환. 미등록 종목이면 empty 반환 (필터링 용도)
    public static Optional<StrategyTicker> tryParse(String name) {
        if (name == null) return Optional.empty();
        try {
            return Optional.of(valueOf(name.trim()));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
