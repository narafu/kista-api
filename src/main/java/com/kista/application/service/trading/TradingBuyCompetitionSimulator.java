package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 바로주문 미리보기에서 계좌 내 활성 전략 전체를 야간 배치와 동일한 우선순위로 시뮬레이션해
// 대상 전략의 BUY가 실제로 승인될지 근사 판정한다
// BUY 전용 — SELL은 계좌당 종목 유일성 제약상 같은 계좌 내에서 경쟁이 발생하지 않는다
// package-private — application/service/trading 패키지 전용
@Component
@RequiredArgsConstructor
@Slf4j
class TradingBuyCompetitionSimulator {

    private final StrategyPort strategyPort;              // 계좌 내 전략 전체 조회
    private final StrategyCyclePort strategyCyclePort;    // 경쟁 전략의 현재 사이클 조회
    private final OrderPort orderPort;                    // 경쟁 전략의 당일 기존 주문 유무 확인
    private final StrategyOrderPlanBuilder planBuilder;   // 경쟁 전략 가상 계산
    private final CycleOrderStrategies cycleOrderStrategies; // 우선순위 조회
    private final PreviewDepositCache depositCache;        // 계좌 단위 라이브 예수금 짧은 TTL 캐시 (preview 전용)

    // 정렬 대상 후보 — 대상 전략과 경쟁 전략을 동일한 형태로 취급
    private record RankedCandidate(UUID strategyId, UUID cycleId, Strategy.Type type,
                                    Strategy.Ticker ticker, BigDecimal requiredBuyUsd, boolean isCurrent) {}

    // 배치 미리보기(TradingPreviewService.previewBatch) 전용 — 계좌 내 전략별 cycle·당일 주문·매매 계획을
    // 미리 계산해 재사용한다. 대상 전략 N개를 순회할 때마다 경쟁 시뮬레이션을 처음부터 다시 계산하던
    // O(N²) KIS 시세 조회·DB 조회를 O(N)으로 줄인다. planResultsByStrategyId에 없는 전략은 캐시 미스로 보고
    // 즉시 재계산을 시도하며, 재계산마저 실패하면 그때 uncertain 처리한다.
    record BatchContext(List<Strategy> strategies, Map<UUID, StrategyCycle> cyclesByStrategyId,
                         Map<UUID, List<Order>> todayOrdersByStrategyId,
                         Map<UUID, StrategyOrderPlanBuilder.PlanResult> planResultsByStrategyId) {}

    BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                    List<Order> currentBuyOrders, LocalDate today,
                                    BigDecimal otherStrategiesPlannedBuyUsd) {
        return simulate(currentStrategy, account, currentCycle, currentBuyOrders, today, otherStrategiesPlannedBuyUsd, null);
    }

    BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                    List<Order> currentBuyOrders, LocalDate today,
                                    BigDecimal otherStrategiesPlannedBuyUsd, BatchContext context) {
        BigDecimal requiredForThis = AccountBalance.buyTotal(currentBuyOrders);

        BigDecimal liveDeposit;
        try {
            // 라이브 예수금에서 타 전략의 당일 PLANNED BUY만 차감 — 대상 전략 자신의 기존 예약분은
            // requiredForThis가 매번 전체 재계산이라 이미 반영돼 있으므로 이중 차감하지 않는다.
            // PLACED 주문은 브로커에 이미 접수돼 라이브 예수금 자체에 반영돼 있어 별도 차감 불필요.
            liveDeposit = depositCache.getUsdDeposit(account, currentStrategy.ticker());
        } catch (KisApiException | TossApiException e) {
            // 브로커 라이브 예수금 조회 자체가 실패(토큰 재시도 소진 등) — 미리보기 전체를 503으로 막지 않고
            // 경쟁 시뮬레이션만 생략한 채 주문 계획(plan.orders())은 정상 반환한다
            log.warn("대상 전략 라이브 예수금 조회 실패, 경쟁 시뮬레이션 생략: strategyId={}, error={}", currentStrategy.id(), e.getMessage());
            return BuyCompetitionPreview.unavailable(requiredForThis);
        }
        BigDecimal availableDeposit = liveDeposit.subtract(otherStrategiesPlannedBuyUsd);

        List<UUID> uncertainStrategyIds = new ArrayList<>();
        List<RankedCandidate> ranked = new ArrayList<>();
        ranked.add(new RankedCandidate(currentStrategy.id(), currentCycle.id(), currentStrategy.type(),
                currentStrategy.ticker(), requiredForThis, true));

        List<Strategy> candidates = context != null ? context.strategies() : strategyPort.findByAccountId(account.id());
        for (Strategy other : candidates) {
            if (other.id().equals(currentStrategy.id()) || other.status() != Strategy.Status.ACTIVE) {
                continue;
            }
            StrategyCycle otherCycle = context != null
                    ? context.cyclesByStrategyId().get(other.id())
                    : strategyCyclePort.findLatestByStrategyId(other.id()).orElse(null);
            if (otherCycle == null) {
                continue; // 사이클 없는 전략은 경쟁 대상이 될 수 없음
            }
            List<Order> otherTodayOrders = context != null
                    ? context.todayOrdersByStrategyId().getOrDefault(other.id(), List.of())
                    : orderPort.findPlannedOrPlacedByCycleAndDate(otherCycle.id(), today);
            boolean alreadyOrdered = !otherTodayOrders.isEmpty();
            if (alreadyOrdered) {
                continue; // PLANNED는 otherStrategiesPlannedBuyUsd에, PLACED는 라이브 예수금에 이미 반영됨
            }

            try {
                StrategyOrderPlanBuilder.PlanResult result = context != null
                        ? context.planResultsByStrategyId().get(other.id())
                        : null;
                if (result == null) {
                    // 캐시 없음(non-batch 호출) 또는 사전 계산 실패(캐시 미스) — 즉시 재계산해 과소평가 방지
                    result = planBuilder.build(other, account, otherCycle, today, "competition:" + other.id());
                }
                if (result.isSkip()) {
                    // NO_CYCLE_HISTORY/NO_PRIVACY_BASE 모두 야간 배치의 실제 동작을 확정할 수 없어
                    // 예외와 동일하게 0으로 취급하며 불확실 목록에 기록한다 (설계 스펙 준수)
                    log.warn("경쟁 시뮬레이션 skip, 0으로 처리: strategyId={}, skipReason={}", other.id(), result.skipReason());
                    uncertainStrategyIds.add(other.id());
                    continue;
                }
                BigDecimal required = AccountBalance.buyTotal(result.plan().orders());
                if (required.signum() > 0) {
                    ranked.add(new RankedCandidate(other.id(), otherCycle.id(), other.type(), other.ticker(), required, false));
                }
            } catch (Exception e) {
                log.warn("경쟁 시뮬레이션 실패, 0으로 처리: strategyId={}, error={}", other.id(), e.getMessage());
                uncertainStrategyIds.add(other.id());
            }
        }

        ranked.sort(BuyPriorityOrdering.comparator(cycleOrderStrategies,
                RankedCandidate::type, RankedCandidate::requiredBuyUsd, RankedCandidate::strategyId, RankedCandidate::cycleId));

        BigDecimal consumedByHigherPriority = BigDecimal.ZERO;
        List<BuyCompetitionPreview.CompetingStrategy> blocked = new ArrayList<>();
        for (RankedCandidate candidate : ranked) {
            if (candidate.isCurrent()) {
                break; // 대상 전략 도달 시 종료 — 이후 후보는 우선순위가 낮아 무관
            }
            consumedByHigherPriority = consumedByHigherPriority.add(candidate.requiredBuyUsd());
            blocked.add(new BuyCompetitionPreview.CompetingStrategy(
                    candidate.strategyId(), candidate.type(), candidate.ticker(),
                    candidate.requiredBuyUsd(), cycleOrderStrategies.of(candidate.type()).allocationPriority()));
        }

        boolean sufficient = consumedByHigherPriority.add(requiredForThis).compareTo(availableDeposit) <= 0;
        return new BuyCompetitionPreview(sufficient, availableDeposit, requiredForThis, consumedByHigherPriority, blocked, uncertainStrategyIds, false);
    }
}
