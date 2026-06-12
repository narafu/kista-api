package com.kista.application.service.trading;

import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.CancelResult;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.OrderCancelException;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
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
    private final BrokerOrderRouter brokerOrderRouter;
    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;

    CancelResult cancelByCycle(UUID strategyId, UUID requesterId) {
        // 소유권 검증: 전략 → 계좌 → 요청자 일치 확인
        var strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle 조회 — 사이클 단위로 취소 범위 격리
        var currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());

        // 오늘 PLACED된 주문 조회 (UTC 기준 — TradeDateConverter 없이 도메인 LocalDate 사용)
        List<Order> placedOrders = orderPort.findPlacedByCycleAndDate(currentCycle.id(), LocalDate.now());
        if (placedOrders.isEmpty()) {
            return new CancelResult(0, 0);
        }

        int cancelledCount = 0;
        int failedCount = 0;

        // best-effort: 개별 주문마다 취소 시도, 실패해도 계속 진행
        for (Order order : placedOrders) {
            try {
                cancelViaBroker(order, account);
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

        // PLACED 상태가 아니면 취소 불가
        if (order.status() != Order.OrderStatus.PLACED) {
            throw new OrderCancelException("PLACED 상태 주문만 취소 가능합니다. 현재 상태: " + order.status());
        }

        cancelViaBroker(order, account);
        orderPort.markCancelled(orderId);
    }

    // 브로커별 주문 취소
    private void cancelViaBroker(Order order, Account account) {
        brokerOrderRouter.cancel(order, account);
    }

}
