package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.model.PrivacyPlan;
import com.kista.tradingcore.matching.domain.model.VrPosition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// DCA 회차 판정 커널 — 순수 계산. 배치 진입은 trading DcaRoundPlanner가 decide()를 직접 호출한다
public class DcaCycleOrderStrategy implements CycleOrderStrategy {

    // DCA 판정 입력 — prevClose: 조회 거래일 US 세션 이전 마지막 확정 종가, sellableQuantity: SELL만 (예약분 차감 후)
    public record DcaInputs(DcaConfig config, StrategyTicker ticker, LocalDate tradeDate,
                            BigDecimal prevClose, Integer sellableQuantity) {}

    @Override
    public StrategyType cycleType() {
        return StrategyType.DCA;
    }

    // DCA는 PlanContext 경로를 쓰지 않는다 — 판정 사유(skip)를 돌려줘야 해서 DcaRoundPlanner → decide()로 진입
    @Override
    public Optional<OrderPlan> plan(PlanContext ctx) {
        throw new UnsupportedOperationException("DCA는 DcaRoundPlanner.decide 경유로만 계획한다");
    }

    // 회차 판정 — 데이터 → 수량 → 잔고 순 (spec 5.2, skip 우선순위 9장)
    public DcaDecision decide(DcaInputs in) {
        BigDecimal prevClose = in.prevClose();
        if (prevClose == null || prevClose.signum() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        }
        DcaConfig c = in.config();
        boolean sell = c.direction() == OrderDirection.SELL;
        // skip 우선순위(spec 9장): DATA_UNAVAILABLE → NO_HOLDINGS(SELL) → BELOW_ONE_SHARE
        if (sell && in.sellableQuantity() == null) {
            return new DcaDecision.Skipped(DcaSkipReason.DATA_UNAVAILABLE, "판매가능수량 조회 실패", prevClose);
        }
        if (sell && in.sellableQuantity() <= 0) {
            return new DcaDecision.Skipped(DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", prevClose);
        }
        int quantity = c.amountUsd().divide(prevClose, 0, RoundingMode.FLOOR).intValueExact();
        if (quantity == 0) {
            return new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE,
                    "회차 금액 " + c.amountUsd().stripTrailingZeros().toPlainString() + " < 전일 종가 " + prevClose.toPlainString(), prevClose);
        }
        if (sell) quantity = Math.min(quantity, in.sellableQuantity()); // 보유 부족 → 보유 전량
        // BUY는 캡 지정가(LOO/LOC로 접수), SELL은 시장가라 가격은 기준가 기록용
        BigDecimal price = c.direction() == OrderDirection.BUY ? PriceCapPolicy.capFor(prevClose) : prevClose;
        PlannedOrder order = PlannedOrder.of(in.tradeDate(), in.ticker(), c.orderType(), c.direction(),
                quantity, price, c.timing(), c.orderLeg());
        return new DcaDecision.Planned(order, prevClose);
    }

    @Override
    public BigDecimal minRequiredDeposit(BigDecimal price, PrivacyPlan privacyPlan, int divisionCount) {
        return null; // 사이클 재등록 없음
    }

    @Override
    public boolean endsCycleOnLiquidation() { return false; } // 영구 사이클

    @Override
    public boolean needsCapCheck(InfinitePosition position, VrPosition vrPosition) { return false; } // 계획 시점 캡가 고정

    @Override
    public int allocationPriority() { return 200; } // 기존 전략 예산 우선 보장

    @Override
    public boolean snapshotsBrokerBalance() { return true; }

    // 예산 부족 시 단일 BUY 수량을 floor(budget / 캡가)로 축소 — 브로커가 지정가×수량으로 주문가능금액을 검증하므로 캡가 기준
    @Override
    public List<PlannedOrder> fitBuysToBudget(List<PlannedOrder> buyOrders, BigDecimal budget) {
        return buyOrders.stream()
                .map(o -> o.withQuantity(budget.divide(o.price(), 0, RoundingMode.FLOOR).intValue()))
                .filter(o -> o.quantity() > 0)
                .toList();
    }
}
