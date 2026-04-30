package com.kista.domain.model;

import java.math.BigDecimal;

public record TradingVariables(
        BigDecimal averagePrice,      // 평단가 (A): 수량>0이면 avgPrice, 수량==0이면 currentPrice
        int quantity,                 // 수량 (Q)
        BigDecimal purchaseAmount,    // 총 매입 금액 (M) = A × Q
        BigDecimal evaluationAmount,  // 현재 평가 금액 (D) = currentPrice × Q
        BigDecimal totalAssets,       // 총 자산액 (B) = 매입 금액 + 예수금
        int totalRounds,              // 전략 총 차수 (예: 20)
        double currentRound,          // 현재 진행 차수 (T) = M ÷ K
        BigDecimal unitAmount,        // 회차별 투자 단위 금액 (K) = B ÷ totalRounds
        BigDecimal targetProfitRate,  // 익절 목표 수익률 (예: 0.20)
        BigDecimal priceOffsetRate,   // 매매 결정 기준 편차율 = targetProfitRate × (1 - 2T/totalRounds)
        BigDecimal usdDeposit,        // 예수금 (D) — 후반 K > D 비교용
        BigDecimal referencePrice,    // 기준가 (G) = A × (1 + S)
        BigDecimal targetPrice,       // 익절 목표가 = A × (1 + targetProfitRate)
        BigDecimal currentPrice       // 현재 시장가
) {}
