package com.kista.matching.domain.model;

import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

public record AccountBalance(
        int holdings,         // 보유 수량
        BigDecimal avgPrice,  // 평균 매입가 (holdings==0이면 null)
        BigDecimal usdDeposit // 통합주문가능금액 (USD, 환전 여부 무관 — TTTC2101R itgr_ord_psbl_amt)
) {
    // applyExecutions()가 필요로 하는 최소 형태 — broker의 Execution은 이 인터페이스를 구현하지 않는다
    // (matching은 broker를 참조하지 않는다) — 호출부가 Execution→Fill로 값을 복제해 감싼다 (인라인 3곳)
    public interface Fill {
        OrderDirection direction();
        int quantity();
        BigDecimal amountUsd();
    }

    // 주문 목록 중 BUY 합계 금액 — hasSufficientDepositFor/TradingOrderBudgetAllocator 공용
    public static BigDecimal buyTotal(List<PlannedOrder> orders) {
        return orders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // 수동 실행용 예수금 검증: 신규 BUY 합계 ≤ (live usdDeposit − 타 전략 당일 PLANNED BUY 합계)
    // 배치 스케쥴러 경로와 달리 타 전략 점유분 차감 포함 — ManualTradingService 전용
    public boolean hasSufficientDepositFor(List<PlannedOrder> orders, BigDecimal otherStrategyBuyTotal) {
        BigDecimal newBuyTotal = buyTotal(orders);
        if (newBuyTotal.compareTo(BigDecimal.ZERO) <= 0) return true; // BUY 없으면 통과
        BigDecimal available = usdDeposit().subtract(otherStrategyBuyTotal);
        return newBuyTotal.compareTo(available) <= 0;
    }

    // 체결 목록 반영 후 매매 후 잔고 — 평단가 = (매도 후 잔여 보유금 + 금일 매수금) ÷ 신규 보유수량 (매도는 평단가 불변)
    public AccountBalance applyExecutions(List<? extends Fill> executions) {
        if (executions.isEmpty()) return this;

        int buyQuantity = sumQuantity(executions, OrderDirection.BUY);
        int sellQuantity = sumQuantity(executions, OrderDirection.SELL);
        BigDecimal buyAmount = sumAmount(executions, OrderDirection.BUY);
        BigDecimal sellAmount = sumAmount(executions, OrderDirection.SELL);

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

    private static int sumQuantity(List<? extends Fill> executions, OrderDirection direction) {
        return executions.stream()
                .filter(e -> e.direction() == direction)
                .mapToInt(Fill::quantity).sum();
    }

    private static BigDecimal sumAmount(List<? extends Fill> executions, OrderDirection direction) {
        return executions.stream()
                .filter(e -> e.direction() == direction)
                .map(Fill::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
