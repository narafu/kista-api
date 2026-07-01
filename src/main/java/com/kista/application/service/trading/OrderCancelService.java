package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class OrderCancelService {

    private final OrderPort orderPort;
    private final BrokerAdapterRegistry registry;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;

    CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        // 소유권 검증: 전략 → 계좌 → 요청자 일치 확인
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle 조회 — 사이클 단위로 취소 범위 격리
        var currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());

        // ManualTradingService와 동일 날짜 기준 사용 (KST 04:00 이후면 +1일 = 수동 실행 tradeDate)
        LocalDate tradeDate = DstInfo.nextTradeDate();

        // PLANNED 주문 먼저 삭제 — 증권사 미접수이므로 DB만 처리
        List<Order> plannedOrders = orderPort.findPlannedByCycleAndDate(currentCycle.id(), tradeDate);
        int plannedDeleted = plannedOrders.size();
        if (!plannedOrders.isEmpty()) {
            orderPort.deletePlannedByCycleAndDate(currentCycle.id(), tradeDate);
            log.info("PLANNED 주문 {}건 삭제 — cycleId={}", plannedDeleted, currentCycle.id());
        }

        // PLACED 주문: 증권사 취소 + DB 상태 변경 (best-effort)
        List<Order> placedOrders = orderPort.findPlacedByCycleAndDate(currentCycle.id(), tradeDate);
        int cancelledCount = plannedDeleted;
        int failedCount = 0;

        for (Order order : placedOrders) {
            try {
                registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
                orderPort.markCancelled(order.id());
                cancelledCount++;
            } catch (Exception e) {
                log.warn("주문 취소 실패 — orderId={}, externalOrderId={}: {}",
                        order.id(), order.externalOrderId(), e.getMessage());
                failedCount++;
            }
        }

        return new CancelResult(cancelledCount, failedCount);
    }

    void cancelOrder(UUID orderId, UUID requesterId) {
        Order order = orderPort.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        // 소유권 검증: 주문의 계좌가 요청자 소유인지 확인
        Account account = accountPort.requireOwnedAccount(order.accountId(), requesterId);

        if (order.status() == Order.OrderStatus.PLANNED) {
            // 증권사 미접수 — DB에서만 취소 처리
            orderPort.markCancelled(orderId);
            return;
        }

        // PLACED 상태: 증권사 취소 후 DB 상태 변경
        if (order.status() != Order.OrderStatus.PLACED) {
            throw new OrderCancelException("취소 가능한 상태가 아닙니다. 현재 상태: " + order.status());
        }

        registry.require(account, BrokerOrderCorrectionPort.class).cancel(order, account);
        orderPort.markCancelled(orderId);
    }

}
