package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderDirection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

// 매수 가격 캡 공통 정책 — INFINITE/PRIVACY(BuyOrderPriceCapper)·VR(VrStrategy) 매수 사다리 공용
public final class PriceCapPolicy {

    // 캡 배수: currentPrice × (1+RATE) 초과 매수가는 캡으로 대체
    private static final BigDecimal CAP_MULTIPLIER = new BigDecimal("1.05");

    private PriceCapPolicy() {
    }

    // currentPrice 기준 캡 가격 계산
    public static BigDecimal capFor(BigDecimal currentPrice) {
        return currentPrice.multiply(CAP_MULTIPLIER).setScale(2, HALF_UP);
    }

    // price가 cap 초과면 cap으로 교체
    public static BigDecimal applyCap(BigDecimal price, BigDecimal cap) {
        return price.compareTo(cap) > 0 ? cap : price;
    }

    // 재산정 BUY는 원래 BUY 슬롯을 채우고, 추가 correction BUY는 기존 상대 순서 뒤에 붙인다 — SELL 등 비-BUY는 원 순서 그대로 유지
    // 캡 초과로 BUY 사다리를 재산정(buildCappedBuyOrders)한 뒤 원본 주문 목록에 순서를 보존하며 병합하는 공통 로직 —
    // 라이브 실행(BuyOrderPriceCapper)·백테스트(BacktestEngine) 양쪽이 동일 구현을 공유
    public static List<PlannedOrder> replaceBuysPreservingOrder(List<PlannedOrder> orders, List<PlannedOrder> cappedBuys) {
        List<PlannedOrder> replaced = new ArrayList<>(orders.size() + cappedBuys.size());
        int cappedIndex = 0;
        for (PlannedOrder order : orders) {
            if (order.direction() != OrderDirection.BUY) {
                replaced.add(order);
            } else if (cappedIndex < cappedBuys.size()) {
                replaced.add(cappedBuys.get(cappedIndex++));
            }
        }
        replaced.addAll(cappedBuys.subList(cappedIndex, cappedBuys.size()));
        return List.copyOf(replaced);
    }
}
