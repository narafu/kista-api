package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.BrokerOrderCorrectionPort;
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
    private final BrokerAdapterRegistry registry;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final NotifyPort notifyPort;

    // 지정된 주문 목록만 증권사 접수 (장 개시 스케쥴러 매도 선접수용 — BUY 보정 없음)
    List<Order> placeGiven(List<Order> orders, Account account) {
        if (orders.isEmpty()) return List.of();
        List<Order> placed = placeEach(orders, account);
        log.info("[{}] 주문 {}건 선접수 (성공)", account.nickname(), placed.size());
        return placed;
    }

    // INFINITE: position 있을 때만 capIfNeeded / PRIVACY: position 없어도 currentPrice 있으면 capPrivacyIfNeeded
    // VR: 가격 캡은 buildOrders 단계에서 이미 적용 — post-hoc 캡 불필요 (strategy.isPrivacy() 가드로 제외)
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position, Strategy strategy) {
        if (currentPrice != null && position != null) {
            buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
        } else if (currentPrice != null && strategy.isPrivacy()) {
            // PRIVACY만: InfinitePosition 없이 단순 가격 캡 적용 (VR은 buildOrders에서 자체 처리)
            buyOrderPriceCapper.capPrivacyIfNeeded(today, account, strategyCycleId, currentPrice);
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
                placedOrder = registry.require(account, BrokerOrderCorrectionPort.class).place(p, account);
            } catch (Exception e) {
                // BUY 실패 시 SELL 포함 나머지 주문 계속 진행 — 잔고 부족은 브로커가 판단
                log.warn("[{}] {} {} 주문 접수 실패: {}", account.nickname(), p.direction(), p.ticker(), e.getMessage());
                notifyPort.notifyError(e);
                orderPort.markFailed(p.id()); // 접수 실패 → FAILED
                continue;
            }
            // 증권사 접수 성공 후 DB 동기화 실패 — 브로커에 주문이 남아있는 불일치 상태 (1회 재시도로 창 축소)
            try {
                markPlacedWithRetry(p.id(), placedOrder.externalOrderId());
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

    // 일시적 DB 오류 흡수 — 1초 후 1회 재시도, 2차 실패는 호출측으로 전파
    private void markPlacedWithRetry(UUID orderId, String externalOrderId) {
        try {
            orderPort.markPlaced(orderId, externalOrderId);
        } catch (Exception first) {
            log.warn("markPlaced 1차 실패 — 1초 후 재시도: {}", first.getMessage());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            orderPort.markPlaced(orderId, externalOrderId);
        }
    }
}
