package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.Quantities;

import java.math.BigDecimal;

// 중간부터 시작 입력 검증 — 전략 등록(RegisterStrategyCommand)·백테스트(BacktestCommand) 공용 규칙
// holdings>0이면 avgPrice>0 필수, 음수 거부. null/0이면 빈 포지션(기존 동작). 주식은 정수 주, 코인은 소수 8자리까지
public final class BootstrapPosition {

    private static final BigDecimal MAX_STOCK_HOLDINGS = BigDecimal.valueOf(Integer.MAX_VALUE); // 주식 int 주수 상한
    private static final int CRYPTO_SCALE = 8; // 코인 수량 소수 자릿수 (orders·cycle_position numeric(20,8))

    private BootstrapPosition() {}

    public static BigDecimal validate(BigDecimal holdings, BigDecimal avgPrice, Market market) {
        if (holdings != null && holdings.signum() < 0) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)은 0 이상이어야 합니다");
        }
        if (avgPrice != null && avgPrice.signum() < 0) {
            throw new IllegalArgumentException("평단가(initialAvgPrice)는 0 이상이어야 합니다");
        }
        BigDecimal normalized = holdings != null ? Quantities.normalize(holdings) : BigDecimal.ZERO;
        // 주식은 int 주수로 소비된다(intValueExact) — 범위 초과는 저장 전에 400으로 거부
        if (market != Market.CRYPTO_KRW && normalized.compareTo(MAX_STOCK_HOLDINGS) > 0) {
            throw new IllegalArgumentException("주식 보유 수량(initialHoldings)이 허용 범위를 넘었습니다");
        }
        if (market == Market.CRYPTO_KRW ? normalized.scale() > CRYPTO_SCALE : normalized.scale() > 0) {
            throw new IllegalArgumentException(market == Market.CRYPTO_KRW
                    ? "보유 수량(initialHoldings)은 소수 8자리까지 입력할 수 있습니다"
                    : "주식 보유 수량(initialHoldings)은 정수여야 합니다");
        }
        if (normalized.signum() > 0 && (avgPrice == null || avgPrice.signum() <= 0)) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)이 있으면 평단가(initialAvgPrice)는 0보다 커야 합니다");
        }
        return normalized;
    }
}
