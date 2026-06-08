package com.kista.domain.model.strategy;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.math.BigDecimal;

import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

public record InfinitePosition(
        AccountBalance balance,
        TradingCycle.Ticker ticker,    // 거래 종목
        BigDecimal prevClosePrice      // 최근 종가 — 0회차에서 평단가 대용 (현재가 대신 사용)
) {
    private static final int TOTAL_ROUNDS = 20;

    // --- 기본 도메인 속성 조회 ---

    public int holdings() {
        return balance.holdings();
    }

    public BigDecimal usdDeposit() {
        return balance.usdDeposit();
    }

    // --- 무한매수법 핵심 수식 연산 (행위) ---

    public BigDecimal averagePrice() {
        // 0회차: 평단가가 없으므로 최근 종가를 평단가 대용으로 사용
        return holdings() == 0 ? prevClosePrice : balance.avgPrice();
    }

    public BigDecimal purchaseAmount() {
        return averagePrice().multiply(BigDecimal.valueOf(holdings()));
    }

    public BigDecimal totalAssets() {
        return usdDeposit().add(purchaseAmount());
    }

    public BigDecimal unitAmount() {
        // B ÷ 20
        return totalAssets()
                .divide(BigDecimal.valueOf(TOTAL_ROUNDS), 2, HALF_UP);
    }

    public double currentRound() {
        return holdings() == 0 ? 0.0
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

    public TradingSnapshot toSnapshot() {
        return new TradingSnapshot(holdings(), averagePrice(), priceOffsetRate(), targetPrice());
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
        BigDecimal halfUnitAmount = unitAmount().divide(BigDecimal.valueOf(2), FLOOR);
        return halfUnitAmount.divide(averagePrice(), 0, FLOOR).intValue();
    }

    public int calcEarlyBuyQuantityByRefPrice(int buyQuantityByAvgPrice) {
        BigDecimal buyAmountByAvgPrice = averagePrice().multiply(BigDecimal.valueOf(buyQuantityByAvgPrice));
        return unitAmount().subtract(buyAmountByAvgPrice)
                .multiply(BigDecimal.ONE.add(ticker.getTargetProfitRate()))
                .divide(referencePrice(), 0, FLOOR).intValue();
    }

    public int calcLateBuyQuantity() {
        return unitAmount().divide(referencePrice(), 0, FLOOR).intValue();
    }

    public int calcLocSellQuantity() {
        return sellQuantityQuarter();
    }

    public int calcLimitSellQuantity() {
        return holdings() - sellQuantityQuarter();
    }

    public int calcMocSellQuantity() {
        return sellQuantityQuarter();
    }

    private int sellQuantityQuarter() {
        return holdings() / 4;
    }
}