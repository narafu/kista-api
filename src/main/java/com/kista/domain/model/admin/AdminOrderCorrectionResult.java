package com.kista.domain.model.admin;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record AdminOrderCorrectionResult(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID orderId,
        AdminOrderCorrectionCommand.Mode mode,
        Order.OrderStatus originalStatus,
        Order.OrderStatus resultingStatus,
        String replacementExternalOrderId,
        int finalHoldings,
        BigDecimal finalAvgPrice,
        BigDecimal finalUsdDeposit,
        Strategy.Status strategyStatus,
        boolean cycleEnded,
        LocalDate cycleEndDate
) {
    // PLANNED_EDIT/PLACED_REPLACE용 — 체결·사이클 필드 없음 (null/0/false)
    public static AdminOrderCorrectionResult simple(AdminOrderCorrectionCommand command, UUID orderId,
                                                    Order.OrderStatus originalStatus, Order.OrderStatus resultingStatus,
                                                    String replacementExternalOrderId, Strategy.Status strategyStatus) {
        return new AdminOrderCorrectionResult(command.userId(), command.accountId(), command.strategyId(),
                orderId, command.mode(), originalStatus, resultingStatus, replacementExternalOrderId,
                0, null, null, strategyStatus, false, null);
    }

    // FILLED_CORRECTION용 — 보정 후 잔고·사이클 종료 정보 포함
    public static AdminOrderCorrectionResult filled(AdminOrderCorrectionCommand command, UUID orderId,
                                                    Order.OrderStatus originalStatus,
                                                    int finalHoldings, BigDecimal finalAvgPrice, BigDecimal finalUsdDeposit,
                                                    Strategy.Status strategyStatus, boolean cycleEnded, LocalDate cycleEndDate) {
        return new AdminOrderCorrectionResult(command.userId(), command.accountId(), command.strategyId(),
                orderId, command.mode(), originalStatus, Order.OrderStatus.FILLED, null,
                finalHoldings, finalAvgPrice, finalUsdDeposit, strategyStatus, cycleEnded, cycleEndDate);
    }
}
