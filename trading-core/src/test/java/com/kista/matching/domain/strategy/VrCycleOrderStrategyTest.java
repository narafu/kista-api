package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.AccountBalance;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.VrPosition;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// VR bootstrap 주문(LOC+AT_CLOSE)이 사다리 캡 재산정(buildCappedBuyOrders) 대상이 아님을 실제 VrStrategy
// 커널로 검증 — mock으로 capBuyOrders를 stub하면 isVrBootstrapShaped 가드 자체가 깨져도 테스트가 못 잡는다
// (BuyOrderPriceCapperTest는 CycleOrderStrategy를 mock해 I/O 오케스트레이션만 검증하므로 이 가드는 별도 커버 필요)
class VrCycleOrderStrategyTest {

    static final LocalDate TODAY = LocalDate.now();

    @Test
    void capBuyOrders_vrBootstrapBuy_notRegeneratedAsLadder() {
        VrStrategy realVrStrategy = new VrStrategy();
        VrCycleOrderStrategy strategy = new VrCycleOrderStrategy(realVrStrategy);

        // V=0, pool>0 bootstrap 포지션 — 사다리 공식(lowerBand=V×(1-bandWidth))이 무의미해 bootstrap 전용 경로를 탄다
        VrPosition bootstrapPosition = new VrPosition(
                new AccountBalance(0, null, new BigDecimal("10000.00")),
                BigDecimal.ZERO, new BigDecimal("15.00"), new BigDecimal("5000.00"), BigDecimal.ZERO, 0);
        // referencePrice=100.00×1.05=105.00 — VrStrategy가 실제로 생성하는 bootstrap 주문 그대로 사용
        PlannedOrder bootstrapBuy = realVrStrategy.buildOrders(bootstrapPosition, StrategyTicker.TQQQ,
                new BigDecimal("100.00"), TODAY).getFirst();
        assertThat(bootstrapBuy.orderType()).isEqualTo(OrderType.LOC); // 픽스처 전제 확인
        assertThat(bootstrapBuy.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(bootstrapBuy.price()).isEqualByComparingTo("105.00");

        // currentPrice=90.00 → cap=94.50 < 105.00(bootstrap 가격) → cap 로직이 트리거되는 조건
        List<PlannedOrder> result = strategy.capBuyOrders(
                List.of(bootstrapBuy), new BigDecimal("94.50"), null, bootstrapPosition, StrategyTicker.TQQQ, TODAY);

        // 가드(isVrBootstrapShaped)가 없었다면 value=0 → lowerBand=0 → 사다리 전부 0원으로 재계산돼
        // 전혀 다른 수량·가격의 LIMIT/AT_OPEN 주문으로 뭉개졌을 것이다 — bootstrap 주문이 원본 그대로 보존되는지 확인
        assertThat(result).containsExactly(bootstrapBuy);
        assertThat(result.getFirst().orderType()).isEqualTo(OrderType.LOC);
        assertThat(result.getFirst().timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(result.getFirst().price()).isEqualByComparingTo("105.00");
    }

    // 예산 축소는 싼 단(꼬리)부터 덜어낸다 — buildBuyLadder의 pool 예산 break와 같은 방향(앞쪽 비싼 단 prefix 유지)
    @Test
    void fitBuysToBudget_trimsCheapestRungsFromTail() {
        VrCycleOrderStrategy strategy = new VrCycleOrderStrategy(new VrStrategy());
        PlannedOrder r1 = ladderBuy(1, 1, "100.00");
        PlannedOrder r2 = ladderBuy(2, 1, "90.00");
        PlannedOrder r3 = ladderBuy(3, 1, "80.00");

        List<PlannedOrder> result = strategy.fitBuysToBudget(List.of(r1, r2, r3), new BigDecimal("195.00"));

        assertThat(result).containsExactly(r1, r2);
    }

    // 캡 병합 단(1주 rung N개 묶음)은 rung 단위로 수량을 줄인다 — 단 통째 제외면 갭다운 시 결과가 비어 캡 없는 원본이 접수된다
    @Test
    void fitBuysToBudget_mergedRung_reducesQuantityKeepingLeg() {
        VrCycleOrderStrategy strategy = new VrCycleOrderStrategy(new VrStrategy());
        PlannedOrder merged = ladderBuy(1, 2, "52.50");
        PlannedOrder r2 = ladderBuy(2, 1, "50.00");

        List<PlannedOrder> result = strategy.fitBuysToBudget(List.of(merged, r2), new BigDecimal("100.00"));

        assertThat(result).containsExactly(merged.withQuantity(1));
        assertThat(result.getFirst().orderLeg()).isEqualTo("VR_BUY_01");
    }

    // 첫 단 1주조차 못 담으면 빈 결과 — 호출부가 거절(allocator)·원본 유지(capper)를 결정한다
    @Test
    void fitBuysToBudget_firstRungUnaffordable_returnsEmpty() {
        VrCycleOrderStrategy strategy = new VrCycleOrderStrategy(new VrStrategy());

        List<PlannedOrder> result = strategy.fitBuysToBudget(
                List.of(ladderBuy(1, 1, "100.00")), new BigDecimal("99.99"));

        assertThat(result).isEmpty();
    }

    // bootstrap(LOC+AT_CLOSE) 배치는 사다리가 아니라 축소하지 않는다
    @Test
    void fitBuysToBudget_bootstrapBatch_returnedAsIs() {
        VrCycleOrderStrategy strategy = new VrCycleOrderStrategy(new VrStrategy());
        PlannedOrder bootstrap = PlannedOrder.of(TODAY, StrategyTicker.TQQQ, OrderType.LOC,
                com.kista.sharedkernel.OrderDirection.BUY, 10, new BigDecimal("105.00"), OrderTiming.AT_CLOSE,
                PlannedOrder.leg("VR_BUY", 1));
        List<PlannedOrder> buys = List.of(bootstrap);

        assertThat(strategy.fitBuysToBudget(buys, new BigDecimal("100.00"))).isSameAs(buys);
    }

    private static PlannedOrder ladderBuy(int rung, int quantity, String price) {
        return PlannedOrder.of(TODAY, StrategyTicker.TQQQ, OrderType.LIMIT, com.kista.sharedkernel.OrderDirection.BUY,
                quantity, new BigDecimal(price), OrderTiming.AT_OPEN, PlannedOrder.leg("VR_BUY", rung));
    }
}
