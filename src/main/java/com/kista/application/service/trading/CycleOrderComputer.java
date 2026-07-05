package com.kista.application.service.trading;

import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.CyclePositionInfiniteDetailPort;
import com.kista.domain.port.out.StrategyInfiniteDetailPort;
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
    private final CyclePositionInfiniteDetailPort cyclePositionInfiniteDetailPort;
    private final StrategyInfiniteDetailPort strategyInfiniteDetailPort;

    // 전략 계산 + 주문 유효성 검증을 묶어 계산만 수행 (부수효과 없음)
    // currentCycle: PRIVACY는 initialUsdDeposit 산출에, INFINITE은 리버스모드 판단에 사용
    // currentPrice: PRIVACY allocateRemainingBudget 분모 산출용 — preview/수동실행 시 null
    // Optional.empty() = 전략 차원 skip (예: PRIVACY 기준매매표 미수신)
    Optional<CycleOrderStrategy.OrderPlan> compute(AccountBalance balance, Strategy strategy, BigDecimal prevClosePrice,
                                                   LocalDate tradeDate, StrategyCycle currentCycle,
                                                   PrivacyTradeBase privacyBase, String label, BigDecimal currentPrice) {
        BigDecimal initialUsdDeposit = strategy.isPrivacy()
                ? currentCycle.startAmount()
                : null;
        Integer divisionCount = resolveDivisionCount(strategy, currentCycle);

        // 오늘의 리버스모드 여부와 첫날 여부를 cycle_position 최근 2건으로 판단
        boolean isReverseMode = false;
        boolean isFirstReverseDay = false;
        BigDecimal starPointPrice = null;
        if (strategy.isInfinite() && currentCycle != null) {
            List<CyclePositionInfiniteDetail> recent = cyclePositionInfiniteDetailPort.findLatestByCycleId(currentCycle.id(), 2);
            isReverseMode = !recent.isEmpty() && recent.get(0).isReverseMode();
            isFirstReverseDay = isReverseMode && (recent.size() < 2 || !recent.get(1).isReverseMode());
            // 별지점: 리버스모드 2일차+에서만 계산 (첫날은 MOC 즉시 청산이라 별지점 불필요)
            if (isReverseMode && !isFirstReverseDay) {
                starPointPrice = computeStarPointPrice(currentCycle.id());
            }
        }

        // 전략 전용 입력을 각 묶음으로 조립 — 타입 무관하게 채울 수 있는 값은 채움 (구현체가 자기 묶음만 소비)
        CycleOrderStrategy.PlanContext.InfiniteInputs infiniteInputs =
                new CycleOrderStrategy.PlanContext.InfiniteInputs(
                        divisionCount, prevClosePrice, starPointPrice, isReverseMode, isFirstReverseDay);
        CycleOrderStrategy.PlanContext.PrivacyInputs privacyInputs =
                new CycleOrderStrategy.PlanContext.PrivacyInputs(initialUsdDeposit, privacyBase, currentPrice);

        CycleOrderStrategy orderStrategy = cycleStrategies.of(strategy);
        // VrInputs: Task 3에서 VR 사이클 상세 조회 후 채울 예정 — 현재 null(VR 전략 등록 가드로 도달 불가)
        return orderStrategy.plan(new CycleOrderStrategy.PlanContext(
                balance, strategy, tradeDate, label, infiniteInputs, privacyInputs, null));
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

    private Integer resolveDivisionCount(Strategy strategy, StrategyCycle currentCycle) {
        if (!strategy.isInfinite()) {
            return null;
        }
        if (currentCycle != null && currentCycle.strategyVersionId() != null) {
            return strategyInfiniteDetailPort.findByStrategyVersionId(currentCycle.strategyVersionId())
                    .map(StrategyInfiniteDetail::divisionCount)
                    .orElse(Strategy.DEFAULT_DIVISION_COUNT);
        }
        return strategyInfiniteDetailPort.findActiveByStrategyId(strategy.id())
                .map(StrategyInfiniteDetail::divisionCount)
                .orElse(Strategy.DEFAULT_DIVISION_COUNT);
    }
}
