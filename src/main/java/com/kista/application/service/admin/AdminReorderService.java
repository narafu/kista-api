package com.kista.application.service.admin;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.common.TimeZones;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.admin.AdminReorderCommand;
import com.kista.domain.model.admin.AdminReorderResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminReorderUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.AuditLogPort;
import com.kista.domain.port.out.MarketCalendarPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.UserPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class AdminReorderService implements AdminReorderUseCase {

    private static final String AUDIT_ACTION = "REORDER"; // 감사 로그 액션 코드
    private static final String AUDIT_TARGET_TYPE = "ORDER";

    private final UserPort userPort;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final AuditLogPort auditLogPort;
    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final MarketCalendarPort marketCalendarPort;

    @Override
    public AdminReorderResult reorder(UUID adminId, AdminReorderCommand command) {
        DstInfo dst = DstInfo.calculate();
        return reorder(adminId, command, dst, Instant.now());
    }

    // 테스트 주입용 — DstInfo + 판정 시각 직접 지정
    AdminReorderResult reorder(UUID adminId, AdminReorderCommand command, DstInfo dst, Instant now) {
        AdminSelectionChain.Selection sel = AdminSelectionChain.resolveAndValidate(
                userPort, accountPort, strategyPort, command.userId(), command.accountId(), command.strategyId());
        User user = sel.user();
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());
        Order sourceOrder = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + command.orderId()));

        AdminSelectionChain.validate(user, account, strategy, sourceOrder);
        if (!sourceOrder.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 재주문할 수 있습니다");
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        Order.OrderDirection direction = command.direction() != null ? command.direction() : sourceOrder.direction();
        LocalDate tradeDate = command.tradeDateKst() != null ? command.tradeDateKst() : sourceOrder.tradeDate();

        // 1. 원본 상태별 취소 처리
        cancelIfNeeded(sourceOrder, account);

        // 2. 주문시점 가용성 서버 측 재검증 (UI disable 우회 방지)
        if (!marketCalendarPort.isMarketOpen(LocalDate.now(TimeZones.KST))) {
            throw new IllegalArgumentException("휴장일에는 재주문할 수 없습니다");
        }
        DstInfo.ReorderTimingAvailability avail = dst.reorderTimingAvailabilityAt(now);
        boolean timingOk = switch (command.timing()) {
            case AT_OPEN -> avail.atOpen();
            case AT_CLOSE -> avail.atClose();
            case IMMEDIATE -> avail.immediate();
        };
        if (!timingOk) {
            throw new IllegalArgumentException("현재 시장 단계에서 " + command.timing() + " 접수가 불가합니다");
        }

        // 3. 재주문 생성 — timing에 따라 PLANNED 저장 또는 즉시 증권사 접수
        Order newOrder = Order.reorder(sourceOrder, tradeDate, direction, quantity, price, command.timing());
        PlacementResult placement = placeOrSave(newOrder, account, command.timing());

        // 4. 감사 로그
        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, sourceOrder.id(),
                auditPayload(command, sourceOrder, direction, quantity, price, placement.status()));

        return new AdminReorderResult(command.userId(), command.accountId(), command.strategyId(),
                sourceOrder.id(), sourceOrder.status(), placement.status(), placement.externalOrderId());
    }

    // 원본 상태에 따라 취소 처리 — PLANNED: DB만 CANCELLED, PLACED: 증권사 취소 + DB CANCELLED
    private void cancelIfNeeded(Order order, Account account) {
        switch (order.status()) {
            case PLANNED -> orderPort.markCancelled(order.id());
            case PLACED -> {
                brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
                orderPort.markCancelled(order.id());
            }
            default -> {} // FILLED/PARTIALLY_FILLED/FAILED/CANCELLED: 이미 종료 상태, no-op
        }
    }

    // AT_OPEN/AT_CLOSE: PLANNED 저장 / IMMEDIATE: 즉시 증권사 접수 (실패 시 FAILED 기록)
    private PlacementResult placeOrSave(Order newOrder, Account account, Order.OrderTiming timing) {
        if (timing == Order.OrderTiming.IMMEDIATE) {
            BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account, BrokerOrderCorrectionPort.class);
            try {
                Order placed = broker.place(newOrder, account);
                orderPort.saveAll(List.of(placed));
                return new PlacementResult(Order.OrderStatus.PLACED, placed.externalOrderId());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(Order.OrderStatus.FAILED, null);
            }
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(Order.OrderStatus.PLANNED, null);
    }

    private record PlacementResult(Order.OrderStatus status, String externalOrderId) {}

    private static BigDecimal requirePrice(AdminReorderCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(AdminReorderCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }

    private static Map<String, Object> auditPayload(AdminReorderCommand command, Order sourceOrder,
                                                     Order.OrderDirection direction, int quantity,
                                                     BigDecimal price, Order.OrderStatus resultingStatus) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("timing", command.timing().name());
        payload.put("strategyId", command.strategyId().toString());
        payload.put("accountId", command.accountId().toString());
        payload.put("orderId", sourceOrder.id().toString());
        payload.put("oldStatus", sourceOrder.status().name());
        payload.put("oldPrice", sourceOrder.price().toPlainString());
        payload.put("oldQuantity", sourceOrder.quantity());
        payload.put("newDirection", direction.name());
        payload.put("newPrice", price.toPlainString());
        payload.put("newQuantity", quantity);
        payload.put("resultingStatus", resultingStatus.name());
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        return payload;
    }
}
