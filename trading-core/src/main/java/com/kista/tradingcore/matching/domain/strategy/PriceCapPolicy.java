package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderDirection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static java.math.RoundingMode.HALF_UP;

// 매수 가격 캡 공통 정책 — INFINITE(리버스 쿼터매수 포함)/PRIVACY(BuyOrderPriceCapper)·VR(VrStrategy) 매수 사다리 공용
// 도입 이유: LOC 지정가가 실제 종가와 크게 벌어지면(경험상 15~20%, 증권사마다 다름) 증권사가 주문 실수로 보고 거부한다
// 5%인 이유: 접수(장 마감 30분 전) 시점 현재가 기준이라 남은 30분 추가 하락에 10~15% 여유를 둔다
// 대가: 마감 30분 내 5% 넘게 반등하면 원래 매수가보다 싸도 미체결 — 캡이 걸리는 날은 이미 급락한 날이라 추가 하락 쪽을 막는다
public final class PriceCapPolicy {

    // 캡 배수: currentPrice × (1+RATE) 초과 매수가는 캡으로 대체
    private static final BigDecimal CAP_MULTIPLIER = new BigDecimal("1.05");

    private PriceCapPolicy() {
    }

    // currentPrice 기준 캡 가격 계산
    public static BigDecimal capFor(BigDecimal currentPrice) {
        return currentPrice.multiply(CAP_MULTIPLIER).setScale(Currency.USD.amountScale(), HALF_UP);
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
