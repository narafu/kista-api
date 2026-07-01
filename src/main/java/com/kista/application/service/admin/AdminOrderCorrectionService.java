package com.kista.application.service.admin;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminOrderCorrectionCommand;
import com.kista.domain.model.admin.AdminOrderCorrectionResult;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminOrderCorrectionUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class AdminOrderCorrectionService implements AdminOrderCorrectionUseCase {

    private final UserPort userPort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CyclePositionPort cyclePositionPort;
    private final OrderPort orderPort;
    private final AuditLogPort auditLogPort;
    private final BrokerAdapterRegistry brokerAdapterRegistry;

    @Override
    public AdminOrderCorrectionResult correctOrder(UUID adminId, AdminOrderCorrectionCommand command) {
        User user = userPort.findByIdOrThrow(command.userId());
        Account account = accountPort.findByIdOrThrow(command.accountId());
        Strategy strategy = strategyPort.findByIdOrThrow(command.strategyId());
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        Order order = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + command.orderId()));

        validateSelectionChain(user, account, strategy, currentCycle, order);

        return switch (command.mode()) {
            case PLANNED_EDIT -> correctPlannedOrder(adminId, command, strategy, order);
            case PLACED_REPLACE -> replacePlacedOrder(adminId, command, account, strategy, order);
            case FILLED_CORRECTION -> correctFilledOrder(adminId, command, strategy, currentCycle, order);
        };
    }

    private AdminOrderCorrectionResult correctPlannedOrder(UUID adminId, AdminOrderCorrectionCommand command,
                                                           Strategy strategy, Order order) {
        requireStatus(order, Order.OrderStatus.PLANNED, command.mode());
        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        orderPort.updatePlannedOrder(order.id(), price, quantity);
        auditLogPort.log(adminId, "ORDER_CORRECTION", "ORDER", order.id(),
                auditPayload(command, order, Map.of(
                        "newPrice", price.toPlainString(),
                        "newQuantity", quantity
                )));
        return new AdminOrderCorrectionResult(
                command.userId(),
                command.accountId(),
                command.strategyId(),
                order.id(),
                command.mode(),
                order.status(),
                Order.OrderStatus.PLANNED,
                null,
                0,
                null,
                null,
                strategy.status(),
                false,
                null
        );
    }

    private AdminOrderCorrectionResult replacePlacedOrder(UUID adminId, AdminOrderCorrectionCommand command,
                                                          Account account, Strategy strategy, Order order) {
        requireStatus(order, Order.OrderStatus.PLACED, command.mode());
        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class);
        broker.cancel(order, account);
        orderPort.markCancelled(order.id());

        Order replacementTemplate = new Order(
                null,
                order.accountId(),
                order.strategyCycleId(),
                command.tradeDateKst() != null ? command.tradeDateKst() : order.tradeDate(),
                order.ticker(),
                order.orderType(),
                order.timing(),
                order.direction(),
                quantity,
                price,
                Order.OrderStatus.PLANNED,
                null,
                null,
                null
        );
        Order replacement = broker.place(replacementTemplate, account);
        orderPort.saveAll(List.of(replacement));

        auditLogPort.log(adminId, "ORDER_CORRECTION", "ORDER", order.id(),
                auditPayload(command, order, Map.of(
                        "newPrice", price.toPlainString(),
                        "newQuantity", quantity,
                        "replacementExternalOrderId", replacement.externalOrderId()
                )));

        return new AdminOrderCorrectionResult(
                command.userId(),
                command.accountId(),
                command.strategyId(),
                order.id(),
                command.mode(),
                order.status(),
                Order.OrderStatus.PLACED,
                replacement.externalOrderId(),
                0,
                null,
                null,
                strategy.status(),
                false,
                null
        );
    }

    private AdminOrderCorrectionResult correctFilledOrder(UUID adminId, AdminOrderCorrectionCommand command,
                                                          Strategy strategy, StrategyCycle currentCycle, Order order) {
        if (order.status() != Order.OrderStatus.FILLED && order.status() != Order.OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalArgumentException("FILLED_CORRECTION은 체결 주문만 지원합니다. 현재 상태: " + order.status());
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        Order.OrderDirection direction = command.direction() != null ? command.direction() : order.direction();
        CyclePosition latest = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));
        AccountBalance balance = new AccountBalance(latest.holdings(), latest.avgPrice(), latest.usdDeposit());
        if (direction == Order.OrderDirection.SELL && quantity > balance.holdings()) {
            throw new IllegalArgumentException("SELL quantity가 현재 holdings를 초과합니다");
        }

        Order correctionOrder = new Order(
                null,
                order.accountId(),
                order.strategyCycleId(),
                command.tradeDateKst() != null ? command.tradeDateKst() : order.tradeDate(),
                order.ticker(),
                Order.OrderType.LIMIT,
                order.timing(),
                direction,
                quantity,
                price,
                Order.OrderStatus.FILLED,
                null,
                quantity,
                price
        );
        Execution execution = new Execution(
                correctionOrder.tradeDate(),
                correctionOrder.ticker(),
                correctionOrder.direction(),
                correctionOrder.quantity(),
                correctionOrder.price(),
                correctionOrder.price().multiply(BigDecimal.valueOf(correctionOrder.quantity())),
                correctionOrder.externalOrderId()
        );
        AccountBalance updatedBalance = balance.applyExecutions(List.of(execution));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updatedBalance, price));

        Strategy updatedStrategy = strategy;
        boolean cycleEnded = false;
        LocalDate cycleEndDate = null;
        if (updatedBalance.holdings() == 0) {
            strategyCyclePort.markEnded(currentCycle.id(), updatedBalance.usdDeposit(), correctionOrder.tradeDate());
            updatedStrategy = strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
            cycleEnded = true;
            cycleEndDate = correctionOrder.tradeDate();
        }

        orderPort.saveAll(List.of(correctionOrder));
        auditLogPort.log(adminId, "ORDER_CORRECTION", "ORDER", order.id(),
                auditPayload(command, order, Map.of(
                        "direction", correctionOrder.direction().name(),
                        "newPrice", price.toPlainString(),
                        "newQuantity", quantity,
                        "cycleEnded", cycleEnded
                )));

        return new AdminOrderCorrectionResult(
                command.userId(),
                command.accountId(),
                command.strategyId(),
                order.id(),
                command.mode(),
                order.status(),
                Order.OrderStatus.FILLED,
                null,
                updatedBalance.holdings(),
                updatedBalance.avgPrice(),
                updatedBalance.usdDeposit(),
                updatedStrategy.status(),
                cycleEnded,
                cycleEndDate
        );
    }

    private void validateSelectionChain(User user, Account account, Strategy strategy,
                                        StrategyCycle currentCycle, Order order) {
        if (!account.userId().equals(user.id())) {
            throw new IllegalArgumentException("account가 user에 속하지 않습니다");
        }
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
        if (!order.accountId().equals(account.id())) {
            throw new IllegalArgumentException("order가 account에 속하지 않습니다");
        }
        if (!order.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 보정할 수 있습니다");
        }
    }

    private static void requireStatus(Order order, Order.OrderStatus expected, AdminOrderCorrectionCommand.Mode mode) {
        if (order.status() != expected) {
            throw new IllegalArgumentException(mode + "는 " + expected + " 주문만 지원합니다. 현재 상태: " + order.status());
        }
    }

    private static BigDecimal requirePrice(AdminOrderCorrectionCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(AdminOrderCorrectionCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }

    private static Map<String, Object> auditPayload(AdminOrderCorrectionCommand command, Order order,
                                                    Map<String, Object> extra) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("mode", command.mode().name());
        payload.put("strategyId", command.strategyId().toString());
        payload.put("accountId", command.accountId().toString());
        payload.put("orderId", order.id().toString());
        payload.put("oldStatus", order.status().name());
        payload.put("oldPrice", order.price().toPlainString());
        payload.put("oldQuantity", order.quantity());
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        payload.putAll(extra);
        return payload;
    }
}
