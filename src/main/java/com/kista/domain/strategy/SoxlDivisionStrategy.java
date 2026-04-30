package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingVariables;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class SoxlDivisionStrategy implements TradingStrategy {

    private static final int TOTAL_ROUNDS = 20;                                  // 전략 총 차수
    private static final BigDecimal TARGET_PROFIT_RATE = new BigDecimal("0.20"); // 익절 목표 수익률

    @Override
    public TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice) {
        // 수량 없으면 현재가를 기준가로 사용 (첫 진입 시 avgPrice가 없음)
        BigDecimal averagePrice = balance.quantity() == 0 ? currentPrice : balance.avgPrice();
        int quantity = balance.quantity();

        // 매입 금액 = 기준가 × 수량
        BigDecimal purchaseAmount = averagePrice.multiply(BigDecimal.valueOf(quantity));
        // 현재 평가 금액 = 현재가 × 수량
        BigDecimal evaluationAmount = currentPrice.multiply(BigDecimal.valueOf(quantity));

        // 총 자산액 = 예수금 + 매입 금액(원가 기준)
        BigDecimal totalAssets = balance.usdDeposit().add(purchaseAmount);
        // 회차별 단위 금액 = 총 자산 ÷ 총 차수
        BigDecimal unitAmount = totalAssets.divide(BigDecimal.valueOf(TOTAL_ROUNDS), 2, RoundingMode.HALF_UP);

        // 현재 진행 차수 = 매입 금액 ÷ 단위 금액 (소수점 허용, 수량 없으면 0)
        double currentRound = quantity == 0 ? 0.0
                : purchaseAmount.divide(unitAmount, 2, RoundingMode.HALF_UP).doubleValue();

        // 매매 결정 기준 편차율 = targetProfitRate × (1 - 2T / totalRounds)
        BigDecimal priceOffsetRate = TARGET_PROFIT_RATE.multiply(
                BigDecimal.ONE.subtract(
                        BigDecimal.valueOf(2.0 * currentRound / TOTAL_ROUNDS)
                )
        ).setScale(4, RoundingMode.HALF_UP);

        // 익절 목표가 = 기준가 × (1 + 목표 수익률)
        BigDecimal targetPrice = averagePrice.multiply(BigDecimal.ONE.add(TARGET_PROFIT_RATE))
                .setScale(2, RoundingMode.HALF_UP);

        return new TradingVariables(
                averagePrice, quantity, purchaseAmount, evaluationAmount,
                totalAssets, TOTAL_ROUNDS, currentRound, unitAmount,
                TARGET_PROFIT_RATE, priceOffsetRate, targetPrice, currentPrice);
    }
}
