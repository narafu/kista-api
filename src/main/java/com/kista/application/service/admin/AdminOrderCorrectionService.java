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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class AdminOrderCorrectionService implements AdminOrderCorrectionUseCase {

    private static final String AUDIT_ACTION = "ORDER_CORRECTION"; // 감사 로그 액션 코드
    private static final String AUDIT_TARGET_TYPE = "ORDER";       // 감사 로그 대상 타입

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

        AdminSelectionChain.validate(user, account, strategy, order);
        if (!order.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 보정할 수 있습니다");
        }

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
        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, order.id(),
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

        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, order.id(),
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
        // 1. 검증
        validateFilledStatus(order);
        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        Order.OrderDirection direction = command.direction() != null ? command.direction() : order.direction();
        AccountBalance balance = loadLatestBalance(currentCycle);
        validateSellQuantity(direction, quantity, balance);

        // 2. 보정 주문 + 체결 내역 빌드
        Order correctionOrder = buildCorrectionOrder(command, order, direction, quantity, price);
        Execution execution = toExecution(correctionOrder);

        // 3. 포지션 업데이트
        AccountBalance updatedBalance = balance.applyExecutions(List.of(execution));
        cyclePositionPort.save(CyclePosition.tradeSnapshot(currentCycle.id(), updatedBalance, price));

        // 4. holdings 소진 시 사이클 종료 처리
        CycleEndResult cycleEnd = closeCycleIfExhausted(strategy, currentCycle, updatedBalance, correctionOrder.tradeDate());

        // 5. 주문 저장 + 감사 로그
        orderPort.saveAll(List.of(correctionOrder));
        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, order.id(),
                auditPayload(command, order, Map.of(
                        "direction", correctionOrder.direction().name(),
                        "newPrice", price.toPlainString(),
                        "newQuantity", quantity,
                        "cycleEnded", cycleEnd.ended()
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
                cycleEnd.strategy().status(),
                cycleEnd.ended(),
                cycleEnd.endDate()
        );
    }

    // 체결 상태 검증 — FILLED_CORRECTION은 FILLED/PARTIALLY_FILLED만 허용
    private static void validateFilledStatus(Order order) {
        if (order.status() != Order.OrderStatus.FILLED && order.status() != Order.OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalArgumentException("FILLED_CORRECTION은 체결 주문만 지원합니다. 현재 상태: " + order.status());
        }
    }

    // 최신 CyclePosition에서 AccountBalance 구성
    private AccountBalance loadLatestBalance(StrategyCycle currentCycle) {
        CyclePosition latest = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("최신 cycle_position이 없습니다: cycleId=" + currentCycle.id()));
        return new AccountBalance(latest.holdings(), latest.avgPrice(), latest.usdDeposit());
    }

    // SELL 수량이 현재 holdings를 초과하는지 검증
    private static void validateSellQuantity(Order.OrderDirection direction, int quantity, AccountBalance balance) {
        if (direction == Order.OrderDirection.SELL && quantity > balance.holdings()) {
            throw new IllegalArgumentException("SELL quantity가 현재 holdings를 초과합니다");
        }
    }

    // 보정 주문(Order) 빌드 — status=FILLED, orderedQuantity/filledPrice 즉시 기록
    private static Order buildCorrectionOrder(AdminOrderCorrectionCommand command, Order order,
                                              Order.OrderDirection direction, int quantity, BigDecimal price) {
        return new Order(
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
    }

    // 보정 주문에서 체결 내역(Execution) 빌드 — applyExecutions에 전달용
    private static Execution toExecution(Order correctionOrder) {
        return new Execution(
                correctionOrder.tradeDate(),
                correctionOrder.ticker(),
                correctionOrder.direction(),
                correctionOrder.quantity(),
                correctionOrder.price(),
                correctionOrder.price().multiply(BigDecimal.valueOf(correctionOrder.quantity())),
                correctionOrder.externalOrderId()
        );
    }

    // holdings 소진(==0) 시 사이클 종료 + 전략 PAUSED 처리
    private CycleEndResult closeCycleIfExhausted(Strategy strategy, StrategyCycle currentCycle,
                                                  AccountBalance updatedBalance, LocalDate tradeDate) {
        if (updatedBalance.holdings() == 0) {
            strategyCyclePort.markEnded(currentCycle.id(), updatedBalance.usdDeposit(), tradeDate);
            Strategy updated = strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
            return new CycleEndResult(updated, true, tradeDate);
        }
        return new CycleEndResult(strategy, false, null);
    }

    // 사이클 종료 여부와 갱신된 전략 상태를 함께 반환하는 값 객체
    record CycleEndResult(Strategy strategy, boolean ended, LocalDate endDate) {}

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
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
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
