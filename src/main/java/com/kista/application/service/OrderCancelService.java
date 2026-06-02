package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.port.in.CancelOrderUseCase;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.KisOrderPort;
import com.kista.domain.port.out.KisReservationOrderPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.TradingCyclePort;
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
public class OrderCancelService implements CancelOrderUseCase {

    private final OrderPort orderPort;
    private final KisOrderPort kisOrderPort;
    private final KisReservationOrderPort kisReservationOrderPort; // 예약주문 취소 (TTTT3017U)
    private final AccountPort accountPort;
    private final TradingCyclePort cyclePort;

    @Override
    public CancelResult cancelByCycle(UUID cycleId, UUID requesterId) {
        // 소유권 검증: 사이클 → 계좌 → 요청자 일치 확인
        var cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId);

        // 오늘 PLACED된 주문 조회 (UTC 기준 — TradeDateConverter 없이 도메인 LocalDate 사용)
        List<Order> placedOrders = orderPort.findPlacedByAccountAndDate(account.id(), LocalDate.now());
        if (placedOrders.isEmpty()) {
            return new CancelResult(0, 0);
        }

        int cancelledCount = 0;
        int failedCount = 0;

        // best-effort: 개별 주문마다 취소 시도, 실패해도 계속 진행
        for (Order order : placedOrders) {
            try {
                cancelViaKis(order, account);
                orderPort.markCancelled(order.id());
                cancelledCount++;
            } catch (Exception e) {
                log.warn("주문 취소 실패 — orderId={}, kisOrderId={}: {}",
                        order.id(), order.kisOrderId(), e.getMessage());
                failedCount++;
            }
        }

        return new CancelResult(cancelledCount, failedCount);
    }

    @Override
    public void cancelOrder(UUID orderId, UUID requesterId) {
        Order order = orderPort.findById(orderId)
                .orElseThrow(() -> new NoSuchElementException("Order not found: " + orderId));

        // 소유권 검증: 주문의 계좌가 요청자 소유인지 확인
        Account account = accountPort.findByIdOrThrow(order.accountId());
        account.verifyOwnedBy(requesterId);

        // PLACED 상태가 아니면 취소 불가
        if (order.status() != Order.OrderStatus.PLACED) {
            throw new IllegalStateException("PLACED 상태 주문만 취소 가능합니다. 현재 상태: " + order.status());
        }

        cancelViaKis(order, account);
        orderPort.markCancelled(orderId);
    }

    // kisOrderId에 "|" 포함이면 예약주문 — TTTT3017U 취소, 아니면 일반 주문 — TTTT1004U 취소
    private void cancelViaKis(Order order, Account account) {
        String kisOrderId = order.kisOrderId();
        if (kisOrderId != null && kisOrderId.contains("|")) {
            // 예약주문 취소: "{ovrs_rsvn_odno}|{receipt_date}" 파싱
            String[] parts = kisOrderId.split("\\|", 2);
            kisReservationOrderPort.cancelReservationOrder(parts[0], parts[1], account);
        } else {
            // 일반 LOC 주문 취소
            kisOrderPort.cancel(order, account);
        }
    }
}
