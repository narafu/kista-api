package com.kista.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

public record InfinitePosition(
        AccountBalance balance,
        Ticker ticker,
        BigDecimal currentPrice
) {
    private static final int TOTAL_ROUNDS = 20;

    // --- 기본 도메인 속성 조회 ---

    public String symbol() {
        return ticker.name();
    }

    public int quantity() {
        return balance.quantity();
    }

    public BigDecimal usdDeposit() {
        return balance.usdDeposit();
    }

    // --- 무한매수법 핵심 수식 연산 (행위) ---

    public BigDecimal averagePrice() {
        return quantity() == 0 ? currentPrice : balance.avgPrice();
    }

    public BigDecimal purchaseAmount() {
        return averagePrice().multiply(BigDecimal.valueOf(quantity()));
    }

    public BigDecimal totalAssets() {
        return usdDeposit().add(purchaseAmount());
    }

    public BigDecimal unitAmount() {
        return totalAssets().divide(BigDecimal.valueOf(TOTAL_ROUNDS), 2, HALF_UP);
    }

    public double currentRound() {
        return quantity() == 0 ? 0.0
                : purchaseAmount().divide(unitAmount(), 2, HALF_UP).doubleValue();
    }

    public BigDecimal priceOffsetRate() {
        double roundFactor = 2.0 * currentRound() / TOTAL_ROUNDS;
        return ticker.getTargetProfitRate()
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(roundFactor)))
                .setScale(4, HALF_UP);
    }

    public BigDecimal referencePrice() {
        return averagePrice().multiply(BigDecimal.ONE.add(priceOffsetRate()))
                .setScale(2, HALF_UP);
    }

    public BigDecimal targetPrice() {
        return averagePrice().multiply(BigDecimal.ONE.add(ticker.getTargetProfitRate()))
                .setScale(2, HALF_UP);
    }

    public BigDecimal evaluationAmount() {
        return currentPrice.multiply(BigDecimal.valueOf(quantity()));
    }

    public TradingSnapshot toSnapshot() {
        return new TradingSnapshot(quantity(), averagePrice(), priceOffsetRate(), targetPrice());
    }

    // --- TDA 지향: 비즈니스 조건 판단 메서드 (Tell, Don't Ask) ---

    public boolean isEarlyStage() {
        return priceOffsetRate().compareTo(BigDecimal.ZERO) > 0;
    }

    public boolean isDepositDeficient() {
        return unitAmount().compareTo(usdDeposit()) > 0;
    }

    // --- 수량 계산 로직 (주문 수량 계산 책임 위임) ---

    public int calcEarlyBuyQuantityByAvgPrice() {
        BigDecimal denominator = averagePrice().multiply(BigDecimal.valueOf(2));
        return unitAmount().divide(denominator, 0, FLOOR).intValue();
    }

    public int calcEarlyBuyQuantityByRefPrice() {
        BigDecimal denominator = referencePrice().multiply(BigDecimal.valueOf(2));
        return unitAmount().divide(denominator, 0, FLOOR).intValue();
    }

    public int calcLateBuyQuantity() {
        return unitAmount().divide(referencePrice(), 0, FLOOR).intValue();
    }

    public int calcLocSellQuantity() {
        return sellQuantityQuarter();
    }

    public int calcLimitSellQuantity() {
        return quantity() - sellQuantityQuarter();
    }

    public int calcMocSellQuantity() {
        return sellQuantityQuarter();
    }

    private int sellQuantityQuarter() {
        return quantity() / 4;
    }
}