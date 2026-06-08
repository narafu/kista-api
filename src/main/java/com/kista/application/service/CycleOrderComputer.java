package com.kista.application.service;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// TradingService/ManualTradingService/TradingPreviewService가 공유하던 주문 계획 공통부 추출
// '전략 라우팅 → plan(PlanContext) → 잔고 유효성 검증'까지만 수행 — 저장·알림·반환 변환은 호출측 책임
@Component
@RequiredArgsConstructor
class CycleOrderComputer {

    private final CycleOrderStrategies cycleStrategies;   // 사이클 타입별 주문 전략 라우터

    // 전략 계산 + 주문 유효성 검증을 묶어 계산만 수행 (부수효과 없음)
    ComputeResult compute(AccountBalance balance, TradingCycle cycle, BigDecimal prevClosePrice,
                          LocalDate tradeDate, PrivacyTradeBase privacyBase, String label) {
        CycleOrderStrategy strategy = cycleStrategies.of(cycle);
        Optional<CycleOrderStrategy.OrderPlan> planOpt = strategy.plan(new CycleOrderStrategy.PlanContext(
                balance, cycle, prevClosePrice, tradeDate, privacyBase, label));

        // 전략 차원 skip (예: PRIVACY 기준매매표 미수신)
        if (planOpt.isEmpty()) return ComputeResult.skipped();

        CycleOrderStrategy.OrderPlan plan = planOpt.get();
        // valid=false: 매수금액 > 잔액 or 매도수량 > 보유수량
        return new ComputeResult(plan, balance.isOrderValid(plan.orders()));
    }

    // plan==null이면 전략 차원 skip / valid는 잔고 유효성 판정 (skip 시 무의미)
    // position은 INFINITE만 non-null — INSUFFICIENT_BALANCE 미리보기에서도 단위금액 전달 위해 보존
    record ComputeResult(CycleOrderStrategy.OrderPlan plan, boolean valid) {
        static ComputeResult skipped() {
            return new ComputeResult(null, false);
        }

        boolean isSkipped() {
            return plan == null;
        }

        InfinitePosition position() {
            return plan != null ? plan.position() : null;
        }

        List<Order> orders() {
            return plan != null ? plan.orders() : List.of();
        }
    }
}
