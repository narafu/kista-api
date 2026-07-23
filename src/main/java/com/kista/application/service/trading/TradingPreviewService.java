package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final StrategyOrderPlanBuilder planBuilder;
    private final TradingBuyCompetitionSimulator competitionSimulator;
    private final TradingPriceFetcher priceFetcher; // 배치 미리보기 전일종가 일괄 조회(getPrevCloses) + 단건 fallback

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회(PRIVACY) 및 경쟁 시뮬레이션에 사용
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        return buildPreview(strategy, account, currentCycle, DstInfo.nextTradeDate(), null, null, null, null);
    }

    // 계좌 내 전략 전체를 한 번의 트랜잭션·요청으로 미리보기 — 목록 화면에서 전략 N개를 개별 호출하던 것을 1회로 축소
    // 사이클 없는 전략은 결과 맵에서 생략(단건 preview는 404지만 배치는 부분 실패를 허용) — 계좌 내 전략 수는 소규모라 O(N) 순회로 충분
    //
    // cycle·당일 주문·매매 계획(planBuilder.build)을 전략별로 1회만 미리 계산해 재사용한다.
    // 이전에는 대상 전략 N개를 순회할 때마다 경쟁 시뮬레이션(TradingBuyCompetitionSimulator)이 계좌 내
    // 다른 전략 전체를 처음부터 다시 계산해 KIS 시세 조회·DB 조회가 O(N²)로 증폭됐다.
    @Transactional(readOnly = true)
    Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);
        LocalDate today = DstInfo.nextTradeDate();
        List<Strategy> strategies = strategyPort.findByAccountId(accountId);

        Map<UUID, StrategyCycle> cyclesByStrategyId = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            strategyCyclePort.findLatestByStrategyId(strategy.id())
                    .ifPresent(cycle -> cyclesByStrategyId.put(strategy.id(), cycle));
        }

        Map<UUID, List<Order>> todayOrdersByStrategyId = new LinkedHashMap<>();
        cyclesByStrategyId.forEach((strategyId, cycle) -> todayOrdersByStrategyId.put(strategyId,
                orderPort.findPlannedOrPlacedByCycleAndDate(cycle.id(), today)));

        // 계좌 내 종목별 전일종가를 1회 일괄 조회(multprice) — 전략마다 개별 KIS 왕복을 생략
        List<Strategy.Ticker> tickers = strategies.stream()
                .filter(s -> cyclesByStrategyId.containsKey(s.id()))
                .map(Strategy::ticker)
                .distinct()
                .toList();
        Map<Strategy.Ticker, BigDecimal> prevCloseCache = tickers.isEmpty() ? Map.of() : priceFetcher.fetchPrevCloses(tickers, account);

        // 계좌 내 당일 PLANNED BUY 합계 — accountId+today로만 결정되는 값(전략 무관)이라 1회만 조회해 재사용
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(accountId, today);

        Map<UUID, StrategyOrderPlanBuilder.PlanResult> planResultsByStrategyId = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = cyclesByStrategyId.get(strategy.id());
            if (cycle == null) continue;
            try {
                planResultsByStrategyId.put(strategy.id(),
                        planBuilder.build(strategy, account, cycle, today, "batch:" + strategy.id(), prevCloseCache));
            } catch (RuntimeException e) {
                // 대상(target) 전략으로 쓰일 때는 buildPreview()가 캐시 누락을 감지해 동일 계산을 재시도하며,
                // 그때 실패하면 기존과 동일하게 전파된다. 경쟁(competitor)으로 쓰일 때도 캐시 미스 시
                // TradingBuyCompetitionSimulator가 즉시 재계산을 시도한다 — 실패한 전략 1개당 최대 1회
                // 추가 계산이 발생해 O(N²) 회귀를 완전히 막지는 못하지만, 과소평가(경쟁 금액 0 처리) 대신
                // 정확성을 우선한 트레이드오프다. 재계산도 실패하면 그때 uncertain 처리한다.
                log.warn("배치 미리보기 사전 계산 실패, 경쟁 시뮬레이션에서 재계산 시도 예정: strategyId={}, error={}",
                        strategy.id(), e.getMessage());
            }
        }

        TradingBuyCompetitionSimulator.BatchContext context = new TradingBuyCompetitionSimulator.BatchContext(
                strategies, cyclesByStrategyId, todayOrdersByStrategyId, planResultsByStrategyId);

        Map<UUID, NextOrdersPreview> previews = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = cyclesByStrategyId.get(strategy.id());
            if (cycle == null) continue;
            previews.put(strategy.id(), buildPreview(strategy, account, cycle, today,
                    todayOrdersByStrategyId.get(strategy.id()), planResultsByStrategyId.get(strategy.id()), context,
                    totalAccountPlannedBuy));
        }
        return previews;
    }

    private NextOrdersPreview buildPreview(Strategy strategy, Account account, StrategyCycle currentCycle, LocalDate today,
                                            List<Order> precomputedTodayOrders,
                                            StrategyOrderPlanBuilder.PlanResult precomputedPlanResult,
                                            TradingBuyCompetitionSimulator.BatchContext context,
                                            BigDecimal precomputedTotalAccountPlannedBuy) {
        // 오늘 이미 등록된 주문 조회 — PLANNED(취소 가능) + PLACED(AT_OPEN 선접수됨) 모두 포함
        List<Order> todayOrders = precomputedTodayOrders != null
                ? precomputedTodayOrders
                : orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);

        // 계좌 내 타 전략 당일 PLANNED BUY 합계 (이 전략 분 제외 — 예수금 부족 계산에 사용)
        // accountId+today로만 결정되는 값이라 배치 호출 시 previewBatch()가 1회 조회한 값을 재사용한다
        BigDecimal totalAccountPlannedBuy = precomputedTotalAccountPlannedBuy != null
                ? precomputedTotalAccountPlannedBuy
                : orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        BigDecimal thisStrategyPlannedBuy = todayOrders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        StrategyOrderPlanBuilder.PlanResult result = precomputedPlanResult != null
                ? precomputedPlanResult
                : planBuilder.build(strategy, account, currentCycle, today, "preview:" + strategy.id());
        if (result.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), result.skipReason(), todayOrders, otherStrategiesPlannedBuyUsd, null);
        }
        CycleOrderStrategy.OrderPlan plan = result.plan();

        // 오늘자 계획에 BUY가 있을 때만 계좌 내 예산 경쟁 시뮬레이션 수행
        List<Order> buyOrders = plan.orders().stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .toList();
        BuyCompetitionPreview competition = buyOrders.isEmpty()
                ? null
                : context != null
                    ? competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd, context)
                    : competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd);

        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayOrders, otherStrategiesPlannedBuyUsd, competition);
    }
}
