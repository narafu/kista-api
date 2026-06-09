package com.kista.domain.model.strategy;

import java.math.BigDecimal;

import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

public record InfinitePosition(
        AccountBalance balance,
        Strategy.Ticker ticker,        // 거래 종목
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

    // static 헬퍼: 실제 평단가·기준가 대신 임의 가격(예: 캡가격)으로도 재사용 가능
    public static int earlyBuyQty1(BigDecimal unitAmount, BigDecimal price1) {
        // 전반 매수①: (K/2) / price1
        return unitAmount.divide(BigDecimal.valueOf(2), FLOOR)
                .divide(price1, 0, FLOOR).intValue();
    }

    public static int earlyBuyQty2(BigDecimal unitAmount, BigDecimal price1, int qty1,
                                   BigDecimal price2, BigDecimal rate) {
        // 전반 매수②: (K - price1*qty1) * (1+r) / price2
        return unitAmount.subtract(price1.multiply(BigDecimal.valueOf(qty1)))
                .multiply(BigDecimal.ONE.add(rate))
                .divide(price2, 0, FLOOR).intValue();
    }

    public static int lateBuyQty(BigDecimal unitAmount, BigDecimal price) {
        // 후반 매수: K / price
        return unitAmount.divide(price, 0, FLOOR).intValue();
    }

    public int calcEarlyBuyQuantityByAvgPrice() {
        return earlyBuyQty1(unitAmount(), averagePrice());
    }

    public int calcEarlyBuyQuantityByRefPrice(int buyQuantityByAvgPrice) {
        return earlyBuyQty2(unitAmount(), averagePrice(), buyQuantityByAvgPrice,
                referencePrice(), ticker.getTargetProfitRate());
    }

    public int calcLateBuyQuantity() {
        return lateBuyQty(unitAmount(), referencePrice());
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