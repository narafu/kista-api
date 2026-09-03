package com.kista.trading.domain.model;

import java.math.BigDecimal;

// 중간부터 시작 입력 검증 — 전략 등록(RegisterStrategyCommand)·백테스트(BacktestCommand) 공용 규칙
// holdings>0이면 avgPrice>0 필수, 음수 거부. null/0이면 빈 포지션(기존 동작)
public final class BootstrapPosition {

    private BootstrapPosition() {}

    public static int validate(Integer holdings, BigDecimal avgPrice) {
        if (holdings != null && holdings < 0) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)은 0 이상이어야 합니다");
        }
        if (avgPrice != null && avgPrice.signum() < 0) {
            throw new IllegalArgumentException("평단가(initialAvgPrice)는 0 이상이어야 합니다");
        }
        int normalizedHoldings = holdings != null ? holdings : 0;
        if (normalizedHoldings > 0 && (avgPrice == null || avgPrice.signum() <= 0)) {
            throw new IllegalArgumentException("보유 수량(initialHoldings)이 있으면 평단가(initialAvgPrice)는 0보다 커야 합니다");
        }
        return normalizedHoldings;
    }
}
