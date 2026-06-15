package com.kista.domain.model.strategy;

import java.math.BigDecimal;

import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

// 리버스모드(소진 후) 포지션 — 소진 발동 이후 사이클에서 별지점 기준 매수/매도 계산
public record ReverseModePosition(
        int holdings,                   // 현재 보유 수량
        BigDecimal avgPrice,            // 평단가
        BigDecimal usdDeposit,          // 가용 잔금
        Strategy.Ticker ticker,         // 거래 종목
        int divisionCount,              // 분할 수 (20/30/40)
        BigDecimal starPointPrice,      // 별지점 = 직전 5거래일 종가 평균 (null이면 계산 불가)
        boolean isFirstDay              // 소진 직후 첫날 여부
) {
    // 첫날 MOC 매도 수량 — holdings / (divisionCount/2)
    // 20분할이면 holdings/10, 40분할이면 holdings/20
    public int calcMocSellQuantity() {
        int divisor = divisionCount / 2;
        return divisor > 0 ? holdings / divisor : 0;
    }

    // 이후 LOC 매도 수량 — 직전보유 / (divisionCount/2)
    public int calcLocSellQuantity() {
        int divisor = divisionCount / 2;
        return divisor > 0 ? holdings / divisor : 0;
    }

    // 쿼터매수 금액 — usdDeposit / 4
    public BigDecimal calcLocBuyAmount() {
        return usdDeposit.divide(BigDecimal.valueOf(4), 2, HALF_UP);
    }

    // 쿼터매수 수량 — 별지점 아래에서 매수: 별지점 - $0.01
    // starPointPrice가 null이거나 0 이하이면 매수 불가 (0 반환)
    public int calcLocBuyQuantity() {
        if (starPointPrice == null || starPointPrice.compareTo(BigDecimal.ZERO) <= 0) return 0;
        BigDecimal buyPrice = starPointPrice.subtract(new BigDecimal("0.01"));
        if (buyPrice.compareTo(BigDecimal.ZERO) <= 0) return 0;
        return calcLocBuyAmount().divide(buyPrice, 0, FLOOR).intValue();
    }

    // 리버스모드 종료 조건: 종가 ≥ 평단 × (1 - targetProfitRate)
    // 종가가 평단 근처 이상으로 회복되면 일반모드로 복귀
    public boolean shouldExitReverseMode(BigDecimal closingPrice, BigDecimal targetProfitRate) {
        if (closingPrice == null || avgPrice == null) return false;
        BigDecimal threshold = avgPrice.multiply(BigDecimal.ONE.subtract(targetProfitRate))
                .setScale(2, HALF_UP);
        return closingPrice.compareTo(threshold) >= 0;
    }
}
