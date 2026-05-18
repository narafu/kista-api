package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.Order;
import com.kista.domain.model.Ticker;
import com.kista.domain.model.TradingVariables;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.Order.OrderDirection.BUY;
import static com.kista.domain.model.Order.OrderDirection.SELL;
import static com.kista.domain.model.Order.OrderStatus.PLACED;
import static com.kista.domain.model.Order.OrderType.LIMIT;
import static com.kista.domain.model.Order.OrderType.LOC;
import static com.kista.domain.model.Order.OrderType.MOC;
import static java.math.RoundingMode.FLOOR;

@Component
public class InfiniteStrategy implements TradingStrategy {

    private static final int TOTAL_ROUNDS = 20; // 전략 총 차수

    @Override
    public TradingVariables calculate(AccountBalance balance, BigDecimal currentPrice, Ticker ticker) {
        BigDecimal targetProfitRate = ticker.getTargetProfitRate(); // 종목별 익절 목표 수익률
        BigDecimal avgPrice = balance.quantity() == 0 ? currentPrice : balance.avgPrice(); // 평단가
        BigDecimal targetPrice = calcTargetPrice(avgPrice, targetProfitRate); // 목표가
        int quantity = balance.quantity(); // 현재 수량

        BigDecimal purchaseAmount = avgPrice.multiply(BigDecimal.valueOf(quantity)); // 매입 금액
        BigDecimal totalAssets = balance.usdDeposit().add(purchaseAmount); // 총 자산
        BigDecimal evaluationAmount = currentPrice.multiply(BigDecimal.valueOf(quantity)); // 평가 금액

        BigDecimal unitAmount = calcUnitAmount(totalAssets); // 매수한도
        double currentRound = calcCurrentRound(quantity, purchaseAmount, unitAmount); // 회차(T)
        BigDecimal priceOffsetRate = calcPriceOffsetRate(currentRound, targetProfitRate); // 별(%)
        BigDecimal referencePrice = calcReferencePrice(avgPrice, priceOffsetRate); // 별(%) 가격

        return TradingVariables.builder()
                .averagePrice(avgPrice).quantity(quantity).purchaseAmount(purchaseAmount)
                .evaluationAmount(evaluationAmount).totalAssets(totalAssets).totalRounds(TOTAL_ROUNDS)
                .currentRound(currentRound).unitAmount(unitAmount).targetProfitRate(targetProfitRate)
                .priceOffsetRate(priceOffsetRate).usdDeposit(balance.usdDeposit())
                .referencePrice(referencePrice).targetPrice(targetPrice).currentPrice(currentPrice)
                .build();
    }

    // 회차별 단위 금액: totalAssets ÷ 20 (scale=2, HALF_UP)
    private BigDecimal calcUnitAmount(BigDecimal totalAssets) {
        return totalAssets.divide(BigDecimal.valueOf(TOTAL_ROUNDS), 2, RoundingMode.HALF_UP);
    }

    // 현재 차수: quantity==0 → 0.0, else purchaseAmount ÷ unitAmount (double)
    private double calcCurrentRound(int quantity, BigDecimal purchaseAmount, BigDecimal unitAmount) {
        return quantity == 0 ? 0.0
                : purchaseAmount.divide(unitAmount, 2, RoundingMode.HALF_UP).doubleValue();
    }

    // 기준률: targetProfitRate × (1 − 2T/20) (scale=4, HALF_UP)
    private BigDecimal calcPriceOffsetRate(double currentRound, BigDecimal targetProfitRate) {
        return targetProfitRate.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(2.0 * currentRound / TOTAL_ROUNDS))
        ).setScale(4, RoundingMode.HALF_UP);
    }

    // 기준가: avgPrice × (1 + priceOffsetRate) (scale=2, HALF_UP)
    private BigDecimal calcReferencePrice(BigDecimal averagePrice, BigDecimal priceOffsetRate) {
        return averagePrice.multiply(BigDecimal.ONE.add(priceOffsetRate))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // 목표가: avgPrice × (1 + targetProfitRate) (scale=2, HALF_UP)
    private BigDecimal calcTargetPrice(BigDecimal averagePrice, BigDecimal targetProfitRate) {
        return averagePrice.multiply(BigDecimal.ONE.add(targetProfitRate))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public List<Order> buildOrders(TradingVariables vars, LocalDate tradeDate, Ticker ticker) {
        String symbol = ticker.name(); // KIS에 전송할 종목 코드
        List<Order> orders = new ArrayList<>();

        BigDecimal G = vars.referencePrice(); // 기준가 = avgPrice × (1+priceOffsetRate)
        BigDecimal A = vars.averagePrice();   // 평단가
        BigDecimal K = vars.unitAmount();     // 회차별 투자금
        BigDecimal P = vars.targetPrice();    // 목표가
        BigDecimal D = vars.usdDeposit();     // 예수금
        int Q = vars.quantity();

        if (vars.priceOffsetRate().compareTo(BigDecimal.ZERO) > 0) {
            // 전반 (priceOffsetRate > 0): LOC 매수 ①② + LOC 매도 + 지정가 매도

            // LOC 매수 ① — 평단가 기준: floor(unitAmount / 2 / avgPrice)
            int buyQty1 = K.divide(A.multiply(BigDecimal.valueOf(2)), 0, FLOOR).intValue();
            if (buyQty1 >= 1)
                orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty1, A, PLACED, null));

            // LOC 매수 ② — 기준가 기준: floor(unitAmount / 2 / referencePrice)
            int buyQty2 = K.divide(G.multiply(BigDecimal.valueOf(2)), 0, FLOOR).intValue();
            if (buyQty2 >= 1)
                orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty2, G, PLACED, null));

            addSellOrders(orders, Q, G, P, tradeDate, symbol);

        } else {
            // 후반 (priceOffsetRate <= 0): 예수금 부족 여부로 분기

            if (K.compareTo(D) > 0) {
                // unitAmount > evaluationAmount: 예수금 부족 → MOC 매도만 (KIS: price=0으로 전송)
                int mocSellQty = Q / 4;
                if (mocSellQty >= 1)
                    orders.add(new Order(tradeDate, symbol, MOC, SELL, mocSellQty,
                            BigDecimal.ZERO, PLACED, null));
            } else {
                // unitAmount <= evaluationAmount: 예수금 충분 → LOC 매수 + LOC 매도 + 지정가 매도

                // LOC 매수 — 기준가 기준: floor(unitAmount / referencePrice)
                int buyQty = K.divide(G, 0, FLOOR).intValue();
                if (buyQty >= 1)
                    orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty, G, PLACED, null));

                addSellOrders(orders, Q, G, P, tradeDate, symbol);
            }
        }

        return orders;
    }

    // LOC 매도(referencePrice+0.01, quantity/4) + 지정가 매도(targetPrice, quantity-quantity/4) — 전반·후반 공통 패턴
    private void addSellOrders(List<Order> orders, int Q, BigDecimal G, BigDecimal P,
                               LocalDate tradeDate, String symbol) {
        int locSellQty = Q / 4;
        if (locSellQty >= 1)
            orders.add(new Order(tradeDate, symbol, LOC, SELL, locSellQty,
                    G.add(new BigDecimal("0.01")), PLACED, null));

        int limitSellQty = Q - Q / 4;
        if (limitSellQty >= 1)
            orders.add(new Order(tradeDate, symbol, LIMIT, SELL, limitSellQty, P, PLACED, null));
    }
}
