package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.VrPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

// VR(밸류리밸런싱) 전략의 주문 계획 + capability 정책
// holdings=0에도 사이클 유지 — endsCycleOnLiquidation()=false
@Slf4j
@Component
@RequiredArgsConstructor
public class VrCycleOrderStrategy implements CycleOrderStrategy {

    private final VrStrategy vrStrategy;

    @Override
    public Strategy.Type cycleType() { return Strategy.Type.VR; }

    @Override
    public boolean supportsReverseMode() { return false; }

    @Override
    public List<Integer> availableDivisionCounts() { return List.of(); }

    @Override
    public boolean requiresPrivacyBase() { return false; }

    @Override
    public boolean requiresPrevClose() { return false; }

    // VR은 전량 청산 후에도 사이클을 유지하며 다시 매수 사다리를 생성
    @Override
    public boolean endsCycleOnLiquidation() { return false; }

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
                inputs.firstCycle(),
                inputs.cycleDue(),
                inputs.remainingTradingDays(),
                inputs.recurringAmount()
        );
        Strategy.Ticker ticker = ctx.strategy().ticker(); // 거래 종목 (strategy에서 결정)
        // inputs.currentPrice(): 스케쥴러 시작 시점 현재가 — null이면 캡 미적용(수동 실행·preview)
        List<Order> orders = vrStrategy.buildOrders(position, ticker, inputs.currentPrice(), ctx.tradeDate());
        log.info("[{}] VR 전략 계산: holdings={}, value={}, lowerBand={}, upperBand={}, orders={}",
                ctx.label(), position.holdings(), position.value(),
                position.lowerBand(), position.upperBand(), orders.size());
        return Optional.of(new OrderPlan(null, orders));
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyTradeBase privacyBase, int divisionCount) {
        // VR은 최소 시드 가드 미적용 (poolLimit 기반으로 자체 제한)
        return null;
    }
}
