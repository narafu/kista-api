package com.kista.domain.model.strategy;

import java.math.BigDecimal;

import static java.math.RoundingMode.FLOOR;
import static java.math.RoundingMode.HALF_UP;

public record InfinitePosition(
        AccountBalance balance,
        Strategy.Ticker ticker,        // 거래 종목
        BigDecimal prevClosePrice,     // 최근 종가 — 0회차에서 평단가 대용 (현재가 대신 사용)
        int divisionCount              // 분할 수 (20/30/40) — totalAssets ÷ divisionCount = unitAmount
) {
    private static final int MONEY_SCALE = 2;              // 금액·가격·회차 반올림 자리수 (센트 단위)
    // LOC/MOC 매도 시 전체 보유 수량의 1/4씩 분할 (4회전 완료 = 전량 매도)
    public static final int SELL_QUARTER_DIVISOR = 4;
    // 미국 주식 호가단위 (센트) — LOC 매도 지정가 보정 등에 공통 사용
    public static final BigDecimal TICK_SIZE = new BigDecimal("0.01");

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
        return totalAssets()
                .divide(BigDecimal.valueOf(divisionCount), MONEY_SCALE, HALF_UP);
    }

    public double currentRound() {
        return holdings() == 0 ? 0.0
                : purchaseAmount().divide(unitAmount(), MONEY_SCALE, HALF_UP).doubleValue();
    }

    // static 헬퍼: DB 저장값(CyclePosition)만으로 회차 계산 — KIS 실시간 조회 없이 StrategyDetail 조립 시 사용
    public static double calcCurrentRound(BigDecimal avgPrice, int holdings, BigDecimal usdDeposit,
                                          int divisionCount) {
        if (divisionCount == 0 || holdings == 0 || avgPrice == null) return 0.0;
        BigDecimal purchaseAmount = avgPrice.multiply(BigDecimal.valueOf(holdings));
        BigDecimal unitAmount = usdDeposit.add(purchaseAmount)
                .divide(BigDecimal.valueOf(divisionCount), MONEY_SCALE, HALF_UP);
        return purchaseAmount.divide(unitAmount, MONEY_SCALE, HALF_UP).doubleValue();
    }

    public BigDecimal priceOffsetRate() {
        // 2는 전체 분할수의 절반(currentRound=divisionCount/2)에서 priceOffsetRate=0이 되도록 하는 계수
        double roundFactor = 2.0 * currentRound() / divisionCount;
        return ticker.getTargetProfitRate()
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(roundFactor)))
                .setScale(MONEY_SCALE, HALF_UP);
    }

    public BigDecimal referencePrice() {
        return averagePrice().multiply(BigDecimal.ONE.add(priceOffsetRate()))
                .setScale(MONEY_SCALE, HALF_UP);
    }

    public BigDecimal targetPrice() {
        return averagePrice().multiply(BigDecimal.ONE.add(ticker.getTargetProfitRate()))
                .setScale(MONEY_SCALE, HALF_UP);
    }

    public TradingSnapshot toSnapshot() {
        return new TradingSnapshot(holdings(), averagePrice(), priceOffsetRate(), targetPrice());
    }

    // --- TDA 지향: 비즈니스 조건 판단 메서드 (Tell, Don't Ask) ---

    // 전반(priceOffsetRate>0) / 후반(priceOffsetRate≤0) — 수학적으로 currentRound < 10 / ≥ 10과 동치
    public boolean isEarlyStage() {
        return priceOffsetRate().compareTo(BigDecimal.ZERO) > 0;
    }

    // 마지막 회차(매도 소진 구간) — unitAmount > usdDeposit ⟺ A·Q > 19·usdDeposit ⟺ currentRound > 19
    public boolean isFinalRound() {
        return unitAmount().compareTo(usdDeposit()) > 0;
    }

    // 체결 후 리버스모드 상태 전이 — prevReverseMode: 이전 상태
    // 진입: 일반모드 + holdings>0 + isFinalRound (소진 발동)
    // 종료: 리버스모드 + holdings>0 + 종가가 회복 임계선 이상 (shouldExitReverseMode)
    public boolean nextReverseMode(boolean prevReverseMode) {
        if (prevReverseMode) {
            // 종료 조건: 종가 ≥ avgPrice × (1 - targetProfitRate)
            if (holdings() > 0 && ReverseModePosition.shouldExit(
                    balance.avgPrice(), prevClosePrice, ticker.getTargetProfitRate())) {
                return false; // 일반모드 복귀
            }
            return true; // 리버스모드 유지
        } else {
            // 진입 조건: isFinalRound (소진 감지)
            return holdings() > 0 && prevClosePrice != null && isFinalRound();
        }
    }

    // --- 수량 계산 로직 (주문 수량 계산 책임 위임) ---

    // static 헬퍼: 실제 평단가·기준가 대신 임의 가격(예: 캡가격)으로도 재사용 가능
    public static int earlyBuyQuantity1(BigDecimal unitAmount, BigDecimal price) {
        // 전반 매수①: (unitAmount/2) / price — 최소 1주 보장
        return Math.max(1, unitAmount.divide(BigDecimal.valueOf(2), FLOOR)
                .divide(price, 0, FLOOR).intValue());
    }

    public static int earlyBuyQuantity2(BigDecimal unitAmount, BigDecimal price1, int quantity,
                                        BigDecimal price2) {
        // 전반 매수②: (unitAmount - price1×quantity) / price2 — 최소 1주 보장
        return Math.max(1, unitAmount.subtract(price1.multiply(BigDecimal.valueOf(quantity)))
                .divide(price2, 0, FLOOR).intValue());
    }

    public static int lateBuyQuantity(BigDecimal unitAmount, BigDecimal price) {
        // 후반 매수: unitAmount / price — FLOOR 결과가 0이어도 최소 1주 보장
        return Math.max(1, unitAmount.divide(price, 0, FLOOR).intValue());
    }

    public int calcEarlyBuyQuantityByAvgPrice() {
        return earlyBuyQuantity1(unitAmount(), averagePrice());
    }

    public int calcEarlyBuyQuantityByRefPrice(int buyQuantityByAvgPrice) {
        return earlyBuyQuantity2(unitAmount(), averagePrice(), buyQuantityByAvgPrice,
                referencePrice());
    }

    public int calcLateBuyQuantity() {
        return lateBuyQuantity(unitAmount(), referencePrice());
    }

    // 전후반 공통 LOC 매도 수량 — 후반 calcMocSellQuantity와 계산식은 같지만 별개 주문(LOC vs MOC)
    public int calcLocSellQuantity() {
        return sellQuantityQuarter();
    }

    public int calcLimitSellQuantity() {
        return holdings() - sellQuantityQuarter();
    }

    // 후반(unitAmount>usdDeposit) MOC 매도 수량 — calcLocSellQuantity와 계산식은 같지만 별개 주문(LOC vs MOC)
    public int calcMocSellQuantity() {
        return sellQuantityQuarter();
    }

    private int sellQuantityQuarter() {
        return holdings() / SELL_QUARTER_DIVISOR;
    }
}
