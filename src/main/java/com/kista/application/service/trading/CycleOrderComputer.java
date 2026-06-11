package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// TradingService/ManualTradingService/TradingPreviewService가 공유하던 주문 계획 공통부 추출
// '전략 라우팅 → plan(PlanContext) → 잔고 유효성 검증'까지만 수행 — 저장·알림·반환 변환은 호출측 책임
@Slf4j
@Component
@RequiredArgsConstructor
class CycleOrderComputer {

    private final CycleOrderStrategies cycleStrategies;   // 전략 타입별 주문 전략 라우터
    private final NotifyPort notifyPort;                  // 잔고 부족 알림 (computeIfValid 전용)

    // 전략 계산 + 주문 유효성 검증을 묶어 계산만 수행 (부수효과 없음)
    // currentCycle: PRIVACY 전략의 initialUsdDeposit(=startAmount) 산출에 사용 — INFINITE는 무시
    ComputeResult compute(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                          LocalDate tradeDate, StrategyCycle currentCycle,
                          PrivacyTradeBase privacyBase, String label) {
        BigDecimal initialUsdDeposit = strategy.type() == Strategy.Type.PRIVACY
                ? currentCycle.startAmount()
                : null;
        CycleOrderStrategy orderStrategy = cycleStrategies.of(strategy);
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderStrategy.plan(new CycleOrderStrategy.PlanContext(
                balance, strategy, initialUsdDeposit, prevClosePrice, tradeDate, privacyBase, label));

        // 전략 차원 skip (예: PRIVACY 기준매매표 미수신)
        if (planOpt.isEmpty()) return ComputeResult.skipped();

        CycleOrderStrategy.OrderPlan plan = planOpt.get();

        // valid=false: 매수금액 > 잔액 or 매도수량 > 보유수량
        return new ComputeResult(plan, balance.isOrderValid(plan.orders()));
    }

    // compute + 잔고 유효성 실패 시 경고 로그·부족 알림까지 수행 — TradingService/ManualTradingService 공용
    // skip 또는 invalid면 Optional.empty() 반환 (invalid인 경우만 알림 발송)
    Optional<ComputeResult> computeIfValid(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                                            LocalDate tradeDate, StrategyCycle currentCycle,
                                            PrivacyTradeBase privacyBase, String label, Account account) {
        ComputeResult result = compute(balance, strategy, prevClosePrice, tradeDate, currentCycle, privacyBase, label);
        if (result.isSkipped()) return Optional.empty();
        if (!result.valid()) {
            log.warn("[{}] 주문 유효성 실패 — 잔액 부족 또는 보유수량 초과", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance, strategy.ticker());
            return Optional.empty();
        }
        return Optional.of(result);
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
