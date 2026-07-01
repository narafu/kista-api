package com.kista.domain.model.strategy;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

public record AccountBalance(
        int holdings,         // 보유 수량
        BigDecimal avgPrice,  // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit // 통합주문가능금액 (USD, 환전 여부 무관 — TTTC2101R itgr_ord_psbl_amt)
) {
    // 주문 유효성 검사: 총 매수금액 > 가용잔액 or 총 매도수량 > 보유수량이면 false
    public boolean isOrderValid(List<Order> orders) {
        BigDecimal totalBuyAmount = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalSellQuantity = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.SELL)
                .mapToInt(Order::quantity).sum();
        return totalBuyAmount.compareTo(usdDeposit) <= 0 && totalSellQuantity <= holdings;
    }

    // 수동 실행용 예수금 검증: 신규 BUY 합계 ≤ (live usdDeposit − 타 전략 당일 PLANNED BUY 합계)
    // 배치 스케쥴러의 isOrderValid()와 달리 타 전략 점유분 차감 포함 — ManualTradingService 전용
    public boolean hasSufficientDepositFor(List<Order> orders, BigDecimal otherStrategyBuyTotal) {
        BigDecimal newBuyTotal = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (newBuyTotal.compareTo(BigDecimal.ZERO) <= 0) return true; // BUY 없으면 통과
        BigDecimal available = usdDeposit().subtract(otherStrategyBuyTotal);
        return newBuyTotal.compareTo(available) <= 0;
    }

    // 체결 목록 반영 후 매매 후 잔고 — 평단가 = (매도 후 잔여 보유금 + 금일 매수금) ÷ 신규 보유수량 (매도는 평단가 불변)
    public AccountBalance applyExecutions(List<Execution> executions) {
        if (executions.isEmpty()) return this;

        int buyQuantity = sumQuantity(executions, Order.OrderDirection.BUY);
        int sellQuantity = sumQuantity(executions, Order.OrderDirection.SELL);
        BigDecimal buyAmount = sumAmount(executions, Order.OrderDirection.BUY);
        BigDecimal sellAmount = sumAmount(executions, Order.OrderDirection.SELL);

        int newHoldings = holdings + buyQuantity - sellQuantity;
        // 매도 후 남은 수량 기준으로 cost basis 산정 — 매도는 평단가에 영향을 주지 않음
        int holdingsAfterSell = Math.max(0, holdings - sellQuantity);
        BigDecimal costAfterSell = (holdingsAfterSell == 0 || avgPrice == null)
                ? BigDecimal.ZERO
                : avgPrice.multiply(BigDecimal.valueOf(holdingsAfterSell));
        BigDecimal newAvgPrice = newHoldings > 0
                ? costAfterSell.add(buyAmount).divide(BigDecimal.valueOf(newHoldings), 4, HALF_UP)
                : null;
        BigDecimal newUsdDeposit = usdDeposit.subtract(buyAmount).add(sellAmount);
        return new AccountBalance(newHoldings, newAvgPrice, newUsdDeposit);
    }

    private static int sumQuantity(List<Execution> executions, Order.OrderDirection direction) {
        return executions.stream()
                .filter(e -> e.direction() == direction)
                .mapToInt(Execution::quantity).sum();
    }

    private static BigDecimal sumAmount(List<Execution> executions, Order.OrderDirection direction) {
        return executions.stream()
                .filter(e -> e.direction() == direction)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
