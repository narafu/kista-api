package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// PriceCapPolicy.replaceBuysPreservingOrder — 매수 캡 재산정 결과를 원본 순서에 병합하는 순수 계산.
// 실주문(BuyOrderPriceCapper.prepareForAllocation)·수동실행(Task3 재사용) 양쪽 실주문 경로가 공유하지만
// 직접 테스트가 없었다(회귀: 최종 리뷰 Finding 2) — 인덱스 소진 규칙을 직접 검증한다.
class PriceCapPolicyTest {

    static final LocalDate TODAY = LocalDate.now();

    private static PlannedOrder buy(String price, int quantity) {
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY, quantity, new BigDecimal(price));
    }

    private static PlannedOrder sell(String price, int quantity) {
        return PlannedOrder.of(TODAY, StrategyTicker.SOXL, OrderType.LIMIT, OrderDirection.SELL, quantity, new BigDecimal(price));
    }

    @Test
    void interleavedSellOrder_staysAtOriginalPosition_untouched() {
        PlannedOrder buy1 = buy("60.00", 1);
        PlannedOrder sellOrder = sell("70.00", 2);
        PlannedOrder buy2 = buy("61.00", 1);
        PlannedOrder cappedBuy1 = buy("52.50", 9);
        PlannedOrder cappedBuy2 = buy("52.50", 8);

        List<PlannedOrder> result = PriceCapPolicy.replaceBuysPreservingOrder(
                List.of(buy1, sellOrder, buy2), List.of(cappedBuy1, cappedBuy2));

        assertThat(result).containsExactly(cappedBuy1, sellOrder, cappedBuy2);
        assertThat(result.get(1)).isSameAs(sellOrder);
    }

    @Test
    void moreCappedBuysThanOriginalSlots_appendsExtraAtEnd() {
        // INFINITE correction 등 base+correction BUY가 원본 BUY 슬롯 수보다 많은 경우
        PlannedOrder originalBuy = buy("60.00", 1);
        PlannedOrder cappedBuy = buy("52.50", 9);
        PlannedOrder correctionBuy = buy("50.00", 1);

        List<PlannedOrder> result = PriceCapPolicy.replaceBuysPreservingOrder(
                List.of(originalBuy), List.of(cappedBuy, correctionBuy));

        assertThat(result).containsExactly(cappedBuy, correctionBuy);
    }

    @Test
    void fewerCappedBuysThanOriginalSlots_dropsLeftoverSlot() {
        // 축소된 VR 사다리 등 재산정 BUY가 원본 BUY 슬롯 수보다 적은 경우 — 남는 슬롯은 그냥 소거된다(null 아님)
        PlannedOrder buy1 = buy("60.00", 1);
        PlannedOrder buy2 = buy("61.00", 1);
        PlannedOrder cappedBuy = buy("52.50", 9);

        List<PlannedOrder> result = PriceCapPolicy.replaceBuysPreservingOrder(
                List.of(buy1, buy2), List.of(cappedBuy));

        assertThat(result).containsExactly(cappedBuy);
    }

    @Test
    void orderLeg_isPreservedThroughReplacement() {
        PlannedOrder originalBuy = buy("60.00", 1);
        PlannedOrder cappedBuy = buy("52.50", 9).withLeg("INFINITE_BUY_02");

        List<PlannedOrder> result = PriceCapPolicy.replaceBuysPreservingOrder(
                List.of(originalBuy), List.of(cappedBuy));

        assertThat(result.get(0).orderLeg()).isEqualTo("INFINITE_BUY_02");
    }

    @Test
    void noBuyOrders_returnsInputUnchanged() {
        PlannedOrder sell1 = sell("70.00", 2);
        PlannedOrder sell2 = sell("71.00", 1);

        List<PlannedOrder> result = PriceCapPolicy.replaceBuysPreservingOrder(
                List.of(sell1, sell2), List.of());

        assertThat(result).containsExactly(sell1, sell2);
    }
}
