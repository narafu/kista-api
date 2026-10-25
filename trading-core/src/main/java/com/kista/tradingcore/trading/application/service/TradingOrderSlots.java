package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 후보(커널 산출 PlannedOrder) 중 오늘자 기존(PLANNED/PLACED, 영속 Order) 주문과 동일 슬롯을 제외한다.
// 슬롯은 timing·direction·leg가 모두 같은 주문만 동일로 본다(leg 없는 과거 주문은 더 이상 생성되지 않는다).
// TradingService(실접수)·TradingPreviewService(미리보기)가 "이미 존재하는 주문" 판정 기준을 공유해
// 두 경로가 어긋나지 않게 한다 — 어긋나면 이미 접수된 주문이 미리보기에서 다시 신규로 잡혀 이중 계산된다.
// package-private — application/service/trading 패키지 전용
final class TradingOrderSlots {

    private record ConcreteSlot(OrderTiming timing, OrderDirection direction, String orderLeg) {
        static ConcreteSlot of(Order order) {
            return new ConcreteSlot(order.timing(), order.direction(), order.orderLeg());
        }
        static ConcreteSlot of(PlannedOrder order) {
            return new ConcreteSlot(order.timing(), order.direction(), order.orderLeg());
        }
    }

    private TradingOrderSlots() {
    }

    static List<PlannedOrder> excludeExisting(List<PlannedOrder> candidates, List<Order> existingOrders) {
        Set<ConcreteSlot> existingSlots = existingOrders.stream()
                .map(ConcreteSlot::of)
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(order -> !existingSlots.contains(ConcreteSlot.of(order)))
                .toList();
    }
}
