package com.kista.application.service.trading;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// TradingService/ManualTradingService/TradingPreviewService가 공유하던 주문 계획 공통부 추출
// '전략 라우팅 → plan(PlanContext) → 잔고 유효성 검증'까지만 수행 — 저장·알림·반환 변환은 호출측 책임
@Slf4j
@Component
@RequiredArgsConstructor
class CycleOrderComputer {

    // 리버스모드 별지점 계산에 사용할 최근 종가 개수
    private static final int STAR_POINT_WINDOW = 5;

    private final CycleOrderStrategies cycleStrategies;   // 전략 타입별 주문 전략 라우터
    private final CyclePositionPort cyclePositionPort;    // 리버스모드 별지점 계산용

    // 전략 계산 + 주문 유효성 검증을 묶어 계산만 수행 (부수효과 없음)
    // currentCycle: PRIVACY는 initialUsdDeposit 산출에, INFINITE은 리버스모드 판단에 사용
    ComputeResult compute(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                          LocalDate tradeDate, StrategyCycle currentCycle,
                          PrivacyTradeBase privacyBase, String label) {
        BigDecimal initialUsdDeposit = strategy.isPrivacy()
                ? currentCycle.startAmount()
                : null;

        // 오늘의 리버스모드 여부와 첫날 여부를 cycle_position 최근 2건으로 판단
        boolean isReverseMode = false;
        boolean isFirstReverseDay = false;
        BigDecimal starPointPrice = null;
        if (strategy.isInfinite() && currentCycle != null) {
            List<CyclePosition> recent = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 2);
            isReverseMode = !recent.isEmpty() && recent.get(0).isReverseMode();
            isFirstReverseDay = isReverseMode && (recent.size() < 2 || !recent.get(1).isReverseMode());
            // 별지점: 리버스모드 2일차+에서만 계산 (첫날은 MOC 즉시 청산이라 별지점 불필요)
            if (isReverseMode && !isFirstReverseDay) {
                starPointPrice = computeStarPointPrice(currentCycle.id());
            }
        }

        CycleOrderStrategy orderStrategy = cycleStrategies.of(strategy);
        Optional<CycleOrderStrategy.OrderPlan> planOpt = orderStrategy.plan(new CycleOrderStrategy.PlanContext(
                balance, strategy, initialUsdDeposit, prevClosePrice, tradeDate, privacyBase, label,
                starPointPrice, isReverseMode, isFirstReverseDay));

        // 전략 차원 skip (예: PRIVACY 기준매매표 미수신)
        if (planOpt.isEmpty()) return ComputeResult.skipped();

        CycleOrderStrategy.OrderPlan plan = planOpt.get();

        return new ComputeResult(plan);
    }

    // 별지점 계산 — 직전 STAR_POINT_WINDOW(5)거래일 종가 평균
    // 포지션이 없으면(첫날) null 반환 — 호출측에서 첫날 분기 처리
    private BigDecimal computeStarPointPrice(UUID cycleId) {
        List<CyclePosition> recentPositions = cyclePositionPort.findLatestByCycleId(cycleId, STAR_POINT_WINDOW);
        List<BigDecimal> closingPrices = recentPositions.stream()
                .map(CyclePosition::closingPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (closingPrices.isEmpty()) return null;
        BigDecimal sum = closingPrices.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(closingPrices.size()), 2, RoundingMode.HALF_UP);
    }

    // 전략 계산 수행 — 전략 차원 skip이면 empty, 아니면 계산 결과 반환
    Optional<ComputeResult> computeUnlessSkipped(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                                                 LocalDate tradeDate, StrategyCycle currentCycle,
                                                 PrivacyTradeBase privacyBase, String label) {
        ComputeResult result = compute(balance, strategy, prevClosePrice, tradeDate, currentCycle, privacyBase, label);
        if (result.isSkipped()) return Optional.empty();
        return Optional.of(result);
    }

    // plan==null이면 전략 차원 skip (PRIVACY 기준매매표 미수신 등)
    record ComputeResult(CycleOrderStrategy.OrderPlan plan) {
        static ComputeResult skipped() {
            return new ComputeResult(null);
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
