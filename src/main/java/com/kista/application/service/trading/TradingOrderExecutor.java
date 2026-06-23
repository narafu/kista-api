package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 증권사 접수: BUY 가격 보정 → PLANNED 개별 접수 → PLACED 마킹 (접수 실패 주문은 로그 후 skip)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderExecutor {

    private final OrderPort orderPort;
    private final BrokerOrderRouter brokerOrderRouter;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final NotifyPort notifyPort;

    // 지정된 주문 목록만 증권사 접수 (장 개시 스케쥴러 매도 선접수용 — BUY 보정 없음)
    List<Order> placeGiven(List<Order> orders, Account account) {
        if (orders.isEmpty()) return List.of();
        List<Order> placed = placeEach(orders, account);
        log.info("[{}] 주문 {}건 선접수 (성공)", account.nickname(), placed.size());
        return placed;
    }

    // position이 있고 currentPrice가 있을 때만 보정 (수동 선행 주문은 그대로 접수)
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position) {
        if (currentPrice != null && position != null) {
            buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
        }
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = placeEach(planned, account);
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }

    // 주문 목록을 개별 접수 — 실패한 주문은 로그 후 건너뜀 (다음 주문 계속 진행)
    private List<Order> placeEach(List<Order> orders, Account account) {
        List<Order> placed = new ArrayList<>();
        for (Order p : orders) {
            Order placedOrder;
            try {
                placedOrder = brokerOrderRouter.place(p, account);
            } catch (Exception e) {
                // BUY 실패 시 SELL 포함 나머지 주문 계속 진행 — 잔고 부족은 브로커가 판단
                log.warn("[{}] {} {} 주문 접수 실패: {}", account.nickname(), p.direction(), p.ticker(), e.getMessage());
                notifyPort.notifyError(e);
                continue;
            }
            // 증권사 접수 성공 후 DB 동기화 실패 — 브로커에 주문이 남아있는 불일치 상태
            try {
                orderPort.markPlaced(p.id(), placedOrder.externalOrderId());
                placed.add(p.withPlaced(placedOrder.externalOrderId()));
            } catch (Exception e) {
                log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                        account.nickname(), p.direction(), p.ticker(), placedOrder.externalOrderId(), e.getMessage());
                notifyPort.notifyError(new IllegalStateException(
                        "[DB 불일치] 증권사 접수 완료 후 PLACED 기록 실패 — externalOrderId=" + placedOrder.externalOrderId(), e));
            }
        }
        return placed;
    }
}
