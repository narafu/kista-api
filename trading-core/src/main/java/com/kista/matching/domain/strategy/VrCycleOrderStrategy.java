package com.kista.matching.domain.strategy;

import com.kista.matching.domain.model.PlannedOrder;
import com.kista.matching.domain.model.PrivacyPlan;
import com.kista.matching.domain.model.InfinitePosition;
import com.kista.matching.domain.model.VrPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// VR(밸류리밸런싱) 전략의 주문 계획 + capability 정책
// holdings=0에도 사이클 유지 — endsCycleOnLiquidation()=false
@Slf4j
@RequiredArgsConstructor
public class VrCycleOrderStrategy implements CycleOrderStrategy {

    private final VrStrategy vrStrategy;

    @Override
    public StrategyType cycleType() { return StrategyType.VR; }

    @Override
    public boolean requiresPrevClose() { return true; }

    // VR은 전량 청산 후에도 사이클을 유지하며 다시 매수 사다리를 생성
    @Override
    public boolean endsCycleOnLiquidation() { return false; }

    @Override
    public boolean requiresRolloverCheck() { return true; }

    @Override
    public int allocationPriority() { return 0; }

    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        PlanContext.VrInputs inputs = ctx.vr();
        // VrPosition 조립 — VrInputs + AccountBalance 결합
        VrPosition position = new VrPosition(
                ctx.balance(),
                inputs.value(),
                inputs.bandWidth(),
                inputs.poolLimit(),
                inputs.poolUsed(),
                inputs.recurringAmount()
        );
        StrategyTicker ticker = ctx.ticker(); // 거래 종목 (strategy에서 결정)
        // referencePrice: bootstrap·캡 판정 공용 기준가(전일종가 대체 허용)
        List<PlannedOrder> orders = vrStrategy.buildOrders(position, ticker, inputs.referencePrice(), ctx.tradeDate());
        log.info("[{}] VR 전략 계산: holdings={}, value={}, lowerBand={}, upperBand={}, orders={}",
                ctx.label(), position.holdings(), position.value(),
                position.lowerBand(), position.upperBand(), orders.size());
        // vrPosition을 OrderPlan에 함께 실어 보낸다 — BuyOrderPriceCapper가 접수 전 VR BUY 재산정(capBuyOrders)에 재사용
        return Optional.of(new OrderPlan(null, position, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        // VR은 최소 시드 가드 미적용 (poolLimit 기반 자체 제한 — poolLimit=0인 무일푼 개장 사이클은 라이브 pool()로 폴백, VrStrategy.governanceLimit 참고)
        return null;
    }

    @Override
    public List<PlannedOrder> capBuyOrders(List<PlannedOrder> buyOrders, BigDecimal cap,
                                            InfinitePosition position, VrPosition vrPosition,
                                            StrategyTicker ticker, LocalDate tradeDate) {
        if (vrPosition == null || isVrBootstrapShaped(buyOrders)) return buyOrders;
        return vrStrategy.buildCappedBuyOrders(vrPosition, ticker, tradeDate, cap);
    }

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) {
        return vrPosition != null;
    }

    // VR bootstrap 주문(LOC+AT_CLOSE)은 사다리 재산정 대상이 아니다 — BuyOrderPriceCapper에서 이동.
    // VrStrategy.buildOrders()는 holdings=0에서 첫 포지션을 못 만든 상태(needsBootstrap)면 bootstrap
    // 주문만 단독 반환하지만, holdings>0인데 사다리 첫 유효 단조차 예산 초과인 드리프트 상태에서는
    // bootstrap BUY(LOC+AT_CLOSE)와 정상 매도 사다리(LIMIT+AT_OPEN)가 같은 배치에 섞여 반환될 수 있다.
    // 사다리 매수는 항상 LIMIT+AT_OPEN이므로, BUY 중 하나라도 LOC이면 이번 배치의 매수가 bootstrap이라는 뜻이다.
    private static boolean isVrBootstrapShaped(List<PlannedOrder> buyOrders) {
        return buyOrders.stream().anyMatch(o -> o.orderType() == com.kista.sharedkernel.OrderType.LOC);
    }
}
