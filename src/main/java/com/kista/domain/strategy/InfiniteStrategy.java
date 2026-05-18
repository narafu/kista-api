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
        BigDecimal A = balance.quantity() == 0 ? currentPrice : balance.avgPrice(); // 평단가
        int Q        = balance.quantity();
        BigDecimal M = A.multiply(BigDecimal.valueOf(Q));               // 매입 금액
        BigDecimal D = currentPrice.multiply(BigDecimal.valueOf(Q));    // 평가 금액
        BigDecimal B = balance.usdDeposit().add(M);                     // 총 자산
        BigDecimal K = calcUnitAmount(B);
        double     T = calcCurrentRound(Q, M, K);
        BigDecimal S = calcPriceOffsetRate(T, targetProfitRate);
        BigDecimal G = calcReferencePrice(A, S);
        BigDecimal P = calcTargetPrice(A, targetProfitRate);

        return TradingVariables.builder()
                .averagePrice(A).quantity(Q).purchaseAmount(M)
                .evaluationAmount(D).totalAssets(B).totalRounds(TOTAL_ROUNDS)
                .currentRound(T).unitAmount(K).targetProfitRate(targetProfitRate)
                .priceOffsetRate(S).usdDeposit(balance.usdDeposit())
                .referencePrice(G).targetPrice(P).currentPrice(currentPrice)
                .build();
    }

    // 회차별 단위 금액: B ÷ 20 (scale=2, HALF_UP)
    private BigDecimal calcUnitAmount(BigDecimal totalAssets) {
        return totalAssets.divide(BigDecimal.valueOf(TOTAL_ROUNDS), 2, RoundingMode.HALF_UP);
    }

    // 현재 차수: Q==0 → 0.0, else M ÷ K (double)
    private double calcCurrentRound(int qty, BigDecimal purchaseAmount, BigDecimal unitAmount) {
        return qty == 0 ? 0.0
                : purchaseAmount.divide(unitAmount, 2, RoundingMode.HALF_UP).doubleValue();
    }

    // 기준률: targetProfitRate × (1 − 2T/20) (scale=4, HALF_UP)
    private BigDecimal calcPriceOffsetRate(double currentRound, BigDecimal targetProfitRate) {
        return targetProfitRate.multiply(
                BigDecimal.ONE.subtract(BigDecimal.valueOf(2.0 * currentRound / TOTAL_ROUNDS))
        ).setScale(4, RoundingMode.HALF_UP);
    }

    // 기준가: A × (1 + S) (scale=2, HALF_UP)
    private BigDecimal calcReferencePrice(BigDecimal averagePrice, BigDecimal priceOffsetRate) {
        return averagePrice.multiply(BigDecimal.ONE.add(priceOffsetRate))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // 목표가: A × (1 + targetProfitRate) (scale=2, HALF_UP)
    private BigDecimal calcTargetPrice(BigDecimal averagePrice, BigDecimal targetProfitRate) {
        return averagePrice.multiply(BigDecimal.ONE.add(targetProfitRate))
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public List<Order> buildOrders(TradingVariables vars, LocalDate tradeDate, Ticker ticker) {
        String symbol = ticker.name(); // KIS에 전송할 종목 코드
        List<Order> orders = new ArrayList<>();

        BigDecimal G = vars.referencePrice(); // 기준가 = A × (1+S)
        BigDecimal A = vars.averagePrice();   // 평단가
        BigDecimal K = vars.unitAmount();     // 회차별 투자금
        BigDecimal P = vars.targetPrice();    // 목표가
        BigDecimal D = vars.usdDeposit();     // 예수금
        int Q = vars.quantity();

        if (vars.priceOffsetRate().compareTo(BigDecimal.ZERO) > 0) {
            // 전반 (priceOffsetRate > 0): LOC 매수 ①② + LOC 매도 + 지정가 매도

            // LOC 매수 ① — 평단가 기준: floor(K / 2 / A)
            int buyQty1 = K.divide(A.multiply(BigDecimal.valueOf(2)), 0, FLOOR).intValue();
            if (buyQty1 >= 1)
                orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty1, A, PLACED, null));

            // LOC 매수 ② — 기준가 기준: floor(K / 2 / G)
            int buyQty2 = K.divide(G.multiply(BigDecimal.valueOf(2)), 0, FLOOR).intValue();
            if (buyQty2 >= 1)
                orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty2, G, PLACED, null));

            addSellOrders(orders, Q, G, P, tradeDate, symbol);

        } else {
            // 후반 (priceOffsetRate <= 0): 예수금 부족 여부로 분기

            if (K.compareTo(D) > 0) {
                // K > D: 예수금 부족 → MOC 매도만 (KIS: price=0으로 전송)
                int mocSellQty = Q / 4;
                if (mocSellQty >= 1)
                    orders.add(new Order(tradeDate, symbol, MOC, SELL, mocSellQty,
                            BigDecimal.ZERO, PLACED, null));
            } else {
                // K <= D: 예수금 충분 → LOC 매수 + LOC 매도 + 지정가 매도

                // LOC 매수 — 기준가 기준: floor(K / G)
                int buyQty = K.divide(G, 0, FLOOR).intValue();
                if (buyQty >= 1)
                    orders.add(new Order(tradeDate, symbol, LOC, BUY, buyQty, G, PLACED, null));

                addSellOrders(orders, Q, G, P, tradeDate, symbol);
            }
        }

        return orders;
    }

    // LOC 매도(G+0.01, Q/4) + 지정가 매도(P, Q-Q/4) — 전반·후반 공통 패턴
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
