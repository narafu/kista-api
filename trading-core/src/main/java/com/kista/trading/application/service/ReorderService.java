package com.kista.trading.application.service;
import com.kista.trading.application.service.support.SelectionChain;

import com.kista.sharedkernel.OrderStatus;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.sharedkernel.TimeZones;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.DstInfo;
import com.kista.trading.domain.model.ReorderCommand;
import com.kista.trading.domain.model.ReorderResult;
import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.application.usecase.ReorderUseCase;
import com.kista.account.application.port.output.AccountPort;
import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.sharedkernel.OrderDirection;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class ReorderService implements ReorderUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final BrokerAdapterRegistry brokerAdapterRegistry;
    private final MarketCalendarPort marketCalendarPort;

    @Override
    public ReorderResult reorder(ReorderCommand command) {
        return reorder(command, DstInfo.calculate(), Instant.now());
    }

    // 테스트 주입용 — DstInfo + 판정 시각 직접 지정
    ReorderResult reorder(ReorderCommand command, DstInfo dst, Instant now) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        Order sourceOrder = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다: " + command.orderId()));

        SelectionChain.validate(command.userId(), account, strategy, sourceOrder);
        if (!sourceOrder.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 재주문할 수 있습니다");
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        OrderDirection direction = command.direction() != null ? command.direction() : sourceOrder.direction();
        LocalDate tradeDate = command.tradeDate() != null ? command.tradeDate() : sourceOrder.tradeDate();

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

        // trading 쪽 독립 감사 기록 — admin 프록시(AdminReorderService.auditLogPort)와 별개로,
        // 내부 엔드포인트를 직접 호출한 경우에도 trading 로그에는 반드시 남긴다
        log.info("재주문 처리: userId={}, accountId={}, strategyId={}, sourceOrderId={}, originalStatus={}, resultingStatus={}, externalOrderId={}",
                command.userId(), command.accountId(), command.strategyId(), sourceOrder.id(),
                sourceOrder.status(), placement.status(), placement.externalOrderId());

        return new ReorderResult(command.userId(), command.accountId(), command.strategyId(),
                sourceOrder.id(), sourceOrder.status(), placement.status(), placement.externalOrderId(),
                sourceOrder.price(), sourceOrder.quantity(), direction);
    }

    // 원본 상태에 따라 취소 처리 — PLANNED: DB만 CANCELLED, PLACED: 증권사 취소 + DB CANCELLED
    private void cancelIfNeeded(Order order, Account account) {
        switch (order.status()) {
            case PLANNED -> orderPort.markCancelled(order.id());
            case PLACED -> {
                brokerAdapterRegistry.require(account.toBrokerRef(), BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
                orderPort.markCancelled(order.id());
            }
            default -> {} // FILLED/PARTIALLY_FILLED/FAILED/CANCELLED: 이미 종료 상태, no-op
        }
    }

    // AT_OPEN/AT_CLOSE: PLANNED 저장 / IMMEDIATE: 즉시 증권사 접수 (실패 시 FAILED 기록)
    private PlacementResult placeOrSave(Order newOrder, Account account, com.kista.sharedkernel.OrderTiming timing) {
        if (timing == com.kista.sharedkernel.OrderTiming.IMMEDIATE) {
            BrokerOrderCorrectionPort broker = brokerAdapterRegistry.require(account.toBrokerRef(), BrokerOrderCorrectionPort.class);
            try {
                OrderInstruction instruction = new OrderInstruction(newOrder.ticker(), newOrder.direction(),
                        newOrder.orderType(), newOrder.quantity(), newOrder.price());
                OrderResult result = broker.place(instruction, account.toBrokerRef());
                Order placed = newOrder.withPlaced(result.externalOrderId());
                orderPort.saveAll(List.of(placed));
                return new PlacementResult(OrderStatus.PLACED, placed.externalOrderId());
            } catch (Exception e) {
                log.warn("재주문 즉시 접수 실패 — FAILED 기록: error={}", e.getMessage());
                orderPort.saveAll(List.of(newOrder.withFailed()));
                return new PlacementResult(OrderStatus.FAILED, null);
            }
        }
        orderPort.saveAll(List.of(newOrder));
        return new PlacementResult(OrderStatus.PLANNED, null);
    }

    private record PlacementResult(OrderStatus status, String externalOrderId) {}

    private static BigDecimal requirePrice(ReorderCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(ReorderCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }
}

