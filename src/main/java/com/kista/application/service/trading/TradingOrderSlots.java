package com.kista.application.service.trading;

import com.kista.domain.model.order.Order;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// 후보 주문 중 오늘자 기존(PLANNED/PLACED) 주문과 동일 슬롯을 제외한다.
// concrete leg는 동일 leg만, legacy UNKNOWN leg는 같은 timing·direction 전체를 기존 점유로 취급한다.
// TradingService(실접수)·TradingPreviewService(미리보기)가 "이미 존재하는 주문" 판정 기준을 공유해
// 두 경로가 어긋나지 않게 한다 — 어긋나면 이미 접수된 주문이 미리보기에서 다시 신규로 잡혀 이중 계산된다.
// package-private — application/service/trading 패키지 전용
final class TradingOrderSlots {

    private record ConcreteSlot(Order.OrderTiming timing, Order.OrderDirection direction, String orderLeg) {
        static ConcreteSlot of(Order order) {
            return new ConcreteSlot(order.timing(), order.direction(), order.orderLeg());
        }
    }

    private record LegacySlot(Order.OrderTiming timing, Order.OrderDirection direction) {
        static LegacySlot of(Order order) {
            return new LegacySlot(order.timing(), order.direction());
        }
    }

    private TradingOrderSlots() {
    }

    static List<Order> excludeExisting(List<Order> candidates, List<Order> existingOrders) {
        Set<ConcreteSlot> existingConcreteSlots = existingOrders.stream()
                .filter(order -> !Order.UNKNOWN_LEG.equals(order.orderLeg()))
                .map(ConcreteSlot::of)
                .collect(Collectors.toSet());
        Set<LegacySlot> existingLegacySlots = existingOrders.stream()
                .filter(order -> Order.UNKNOWN_LEG.equals(order.orderLeg()))
                .map(LegacySlot::of)
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(order -> !existingLegacySlots.contains(LegacySlot.of(order)))
                .filter(order -> !existingConcreteSlots.contains(ConcreteSlot.of(order)))
                .toList();
    }
}
