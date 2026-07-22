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

    BuyCompetitionPreview simulate(Strategy currentStrategy, Account account, StrategyCycle currentCycle,
                                    List<Order> currentBuyOrders, LocalDate today,
                                    BigDecimal otherStrategiesPlannedBuyUsd) {
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

        for (Strategy other : strategyPort.findByAccountId(account.id())) {
            if (other.id().equals(currentStrategy.id()) || other.status() != Strategy.Status.ACTIVE) {
                continue;
            }
            StrategyCycle otherCycle = strategyCyclePort.findLatestByStrategyId(other.id()).orElse(null);
            if (otherCycle == null) {
                continue; // 사이클 없는 전략은 경쟁 대상이 될 수 없음
            }
            boolean alreadyOrdered = !orderPort.findPlannedOrPlacedByCycleAndDate(otherCycle.id(), today).isEmpty();
            if (alreadyOrdered) {
                continue; // PLANNED는 otherStrategiesPlannedBuyUsd에, PLACED는 라이브 예수금에 이미 반영됨
            }

            try {
                StrategyOrderPlanBuilder.PlanResult result =
                        planBuilder.build(other, account, otherCycle, today, "competition:" + other.id());
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
