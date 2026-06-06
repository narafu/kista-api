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
    // 체결 목록 반영 후 매매 후 잔고 — 평단가 = (기존 매입금 + 금일 매수금) ÷ 신규 보유수량 (매도는 평단가 불변)
    public AccountBalance applyExecutions(List<Execution> executions) {
        if (executions.isEmpty()) return this;

        int buyQty = sumQuantity(executions, Order.OrderDirection.BUY);
        int sellQty = sumQuantity(executions, Order.OrderDirection.SELL);
        BigDecimal buyAmount = sumAmount(executions, Order.OrderDirection.BUY);
        BigDecimal sellAmount = sumAmount(executions, Order.OrderDirection.SELL);

        int newHoldings = holdings + buyQty - sellQty;
        BigDecimal preAmount = (holdings == 0 || avgPrice == null)
                ? BigDecimal.ZERO
                : avgPrice.multiply(BigDecimal.valueOf(holdings));
        BigDecimal newAvgPrice = newHoldings > 0
                ? preAmount.add(buyAmount).divide(BigDecimal.valueOf(newHoldings), 4, HALF_UP)
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
