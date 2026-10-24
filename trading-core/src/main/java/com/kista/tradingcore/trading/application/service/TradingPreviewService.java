package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.LedgerCheckPreview;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.DcaManualExecutionUnsupportedException;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import com.kista.shared.kernel.StrategyTicker;

@Slf4j
@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final StrategyOrderPlanBuilder planBuilder;
    private final StrategyLedgerBudget ledgerBudget; // 배치·수동실행과 같은 전략 원장 상한 판정
    private final TradingPriceFetcher priceFetcher; // 배치 미리보기 전일종가 일괄 조회(getPrevCloses) + 단건 fallback

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    // 내부적으로 previewBatch()에 위임한다 — 단건과 배치를 별도 코드 경로로 유지할 이유가 없다.
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        // 코인 DCA는 실행 루프(CryptoDcaRoundExecutor) 전용이라 미리보기 경로가 없다 — 주식 DCA는 plan() 정규 경로로 미리보기
        if (TradingAccount.from(account).brokerRef().broker().isCryptoExchange()) throw new DcaManualExecutionUnsupportedException();
        // 계산 실패로 생략된 전략은 404(사이클 없음)로 오인되지 않도록 원래 예외를 그대로 전파
        Map<UUID, RuntimeException> failures = new HashMap<>();
        NextOrdersPreview preview = previewBatch(strategy.accountId(), requesterId, failures).get(strategyId);
        if (preview != null) return preview;
        if (failures.containsKey(strategyId)) throw failures.get(strategyId);
        throw new NoSuchElementException("활성 사이클 없음: strategyId=" + strategyId);
    }

    // 계좌 내 전략 전체를 한 번의 트랜잭션·요청으로 미리보기 — 목록 화면에서 전략 N개를 개별 호출하던 것을 1회로 축소
    // 사이클 없는 전략은 결과 맵에서 생략(단건 preview는 404지만 배치는 부분 실패를 허용) — 계좌 내 전략 수는 소규모라 O(N) 순회로 충분
    //
    // cycle·당일 주문·매매 계획(planBuilder.build)을 전략별로 1회만 미리 계산해 재사용한다.
    @Transactional(readOnly = true)
    Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId) {
        return previewBatch(accountId, requesterId, new HashMap<>());
    }

    // failures: 재시도까지 실패해 결과에서 생략된 전략의 예외 — 단건 preview가 원인을 그대로 응답하도록 수집
    private Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId, Map<UUID, RuntimeException> failures) {
        // 소유권 검증은 Account 애그리게이트로 끝내고, 이후 프리뷰 경로는 투영(TradingAccount)만 사용
        Account ownedAccount = accountPort.requireOwnedAccount(accountId, requesterId);
        TradingAccount account = TradingAccount.from(ownedAccount);
        LocalDate today = DstInfo.nextTradeDate();
        // 코인 전략(전부 DCA)은 미리보기 대상이 아니다 — 결과 맵에서 빠진다. 주식 DCA는 포함
        List<Strategy> strategies = account.brokerRef().broker().isCryptoExchange() ? List.of() : strategyPort.findByAccountId(accountId);

        // 전략 목록의 현재 사이클을 1회 배치 조회 (전략마다 개별 조회하던 것을 축소)
        List<UUID> strategyIds = strategies.stream().map(Strategy::id).toList();
        Map<UUID, StrategyCycle> latestCycles = strategyCyclePort.findLatestByStrategyIds(strategyIds);
        Map<UUID, StrategyCycle> cyclesByStrategyId = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = latestCycles.get(strategy.id());
            if (cycle != null) cyclesByStrategyId.put(strategy.id(), cycle);
        }

        // 사이클들의 오늘 PLANNED/PLACED 주문을 1회 배치 조회 (사이클마다 개별 조회하던 것을 축소)
        List<UUID> cycleIds = cyclesByStrategyId.values().stream().map(StrategyCycle::id).toList();
        Map<UUID, List<Order>> ordersByCycleId = orderPort.findPlannedOrPlacedByCycleIdsAndDate(cycleIds, today);
        Map<UUID, List<Order>> todayOrdersByStrategyId = new LinkedHashMap<>();
        cyclesByStrategyId.forEach((strategyId, cycle) -> todayOrdersByStrategyId.put(strategyId,
                ordersByCycleId.getOrDefault(cycle.id(), List.of())));

        // 계좌 내 종목별 전일종가를 1회 일괄 조회(multprice) — 전략마다 개별 KIS 왕복을 생략
        List<StrategyTicker> tickers = strategies.stream()
                .filter(s -> cyclesByStrategyId.containsKey(s.id()))
                .map(Strategy::ticker)
                .distinct()
                .toList();
        Map<StrategyTicker, BigDecimal> prevCloseCache = tickers.isEmpty() ? Map.of() : priceFetcher.fetchPrevCloses(tickers, account.brokerRef());

        // 계좌 내 당일 PLANNED BUY 합계 — accountId+today로만 결정되는 값(전략 무관)이라 1회만 조회해 재사용
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(accountId, today);

        Map<UUID, StrategyOrderPlanBuilder.PlanResult> planResultsByStrategyId = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = cyclesByStrategyId.get(strategy.id());
            if (cycle == null) continue;
            if (cycle.endDate() != null) {
                // findLatestByStrategyId는 종료 여부 무관 최신 1건 — 청산 후 재등록 실패로 종료된 사이클이
                // "현재 사이클"로 남아있으면 여기서 걸러야 다음 주문이 잘못 계산되지 않는다.
                // startDate==endDate==오늘(당일 등록·당일 청산)인 경우와 겹칠 수 있어 시작예정일 체크보다 먼저 판정한다
                planResultsByStrategyId.put(strategy.id(),
                        new StrategyOrderPlanBuilder.PlanResult(null, NextOrdersPreview.SkipReason.CYCLE_ENDED));
                continue;
            }
            if (!today.isAfter(cycle.startDate())) {
                // skip 결과를 캐시에 명시적으로 채워둔다 — 비워두면 아래 최종 루프가 캐시 미스로 오인해 재계산한다
                planResultsByStrategyId.put(strategy.id(),
                        new StrategyOrderPlanBuilder.PlanResult(null, NextOrdersPreview.SkipReason.SCHEDULED_START_NOT_REACHED));
                continue;
            }
            try {
                planResultsByStrategyId.put(strategy.id(),
                        planBuilder.build(strategy, account, cycle, today, "batch:" + strategy.id(), prevCloseCache));
            } catch (RuntimeException e) {
                // 아래 최종 루프가 캐시 누락을 감지해 동일 계산을 재시도하며, 그때 실패하면 그대로 전파된다
                log.warn("배치 미리보기 사전 계산 실패, 재시도 예정: strategyId={}, error={}",
                        strategy.id(), e.getMessage());
            }
        }

        Map<UUID, NextOrdersPreview> previews = new LinkedHashMap<>();
        for (Strategy strategy : strategies) {
            StrategyCycle cycle = cyclesByStrategyId.get(strategy.id());
            if (cycle == null) continue;
            StrategyOrderPlanBuilder.PlanResult planResult = planResultsByStrategyId.get(strategy.id());
            if (planResult == null) {
                // 사전계산 단계에서 이 전략의 build()가 실패해 캐시에 없는 경우 — 즉시 재시도.
                // 재시도도 실패하면 이 전략만 결과 맵에서 생략한다 — 위 "사이클 없는 전략은 결과 맵에서
                // 생략" 처리와 동일한 부분 실패 허용 정책이다. 한 전략의 지속적 계산 실패가 preview(단건)
                // 호출 시 계좌 내 다른(건강한) 전략까지 실패시키는 회귀를 막는다.
                try {
                    planResult = planBuilder.build(strategy, account, cycle, today, "preview:" + strategy.id(), prevCloseCache);
                } catch (RuntimeException e) {
                    log.warn("배치 미리보기 재시도도 실패 — 이 전략만 생략: strategyId={}, error={}", strategy.id(), e.getMessage());
                    failures.put(strategy.id(), e);
                    continue;
                }
            }
            previews.put(strategy.id(), buildPreview(strategy, account, cycle, today,
                    todayOrdersByStrategyId.get(strategy.id()), planResult, totalAccountPlannedBuy));
        }
        return previews;
    }

    private NextOrdersPreview buildPreview(Strategy strategy, TradingAccount account, StrategyCycle currentCycle, LocalDate today,
                                            List<Order> todayOrders,
                                            StrategyOrderPlanBuilder.PlanResult planResult,
                                            BigDecimal totalAccountPlannedBuy) {
        BigDecimal thisStrategyPlannedBuy = todayOrders.stream()
                .filter(o -> o.direction() == OrderDirection.BUY)
                .map(o -> o.price().multiply(o.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        // 최신 사이클이 종료됨(endDate 존재) — 청산 후 재등록 실패로 남은 상태. findLatestByStrategyId가
        // 종료 여부를 안 가리므로 여기서 별도로 걸러야 종료된 사이클 기준 주문이 나가지 않는다.
        // startDate==endDate==오늘(당일 등록·당일 청산)인 경우와 겹칠 수 있어 시작예정일 체크보다 먼저 판정한다
        if (currentCycle.endDate() != null) {
            return new NextOrdersPreview(today, null, List.of(), NextOrdersPreview.SkipReason.CYCLE_ENDED,
                    todayOrders, otherStrategiesPlannedBuyUsd, null);
        }
        // 시작예정일 미도래 사이클은 계획 계산 자체를 건너뛴다 — TradingService.filterScheduledStart와 동일 기준(tradeDate > startDate)
        if (!today.isAfter(currentCycle.startDate())) {
            return new NextOrdersPreview(today, null, List.of(), NextOrdersPreview.SkipReason.SCHEDULED_START_NOT_REACHED,
                    todayOrders, otherStrategiesPlannedBuyUsd, null);
        }

        if (planResult.isSkip()) {
            return new NextOrdersPreview(today, null, List.of(), planResult.skipReason(), todayOrders, otherStrategiesPlannedBuyUsd, null);
        }
        CycleOrderStrategy.OrderPlan plan = planResult.plan();

        // 배치(TradingCandidatePlanner)와 같은 판정 — 이미 저장된 슬롯을 뺀 신규 주문만 원장 잔여에 대조한다
        // DCA(원장 상한 제외)·신규 BUY 없음이면 경고 없음(null)
        List<PlannedOrder> freshOrders = TradingOrderSlots.excludeExisting(plan.orders(), todayOrders);
        LedgerCheckPreview ledgerCheck = null;
        if (ledgerBudget.applies(strategy.type()) && freshOrders.stream().anyMatch(o -> o.direction() == OrderDirection.BUY)) {
            BigDecimal remainingLedger = ledgerBudget.remaining(currentCycle, today);
            StrategyLedgerBudget.LedgerFit fit = ledgerBudget.fit(strategy.type(), freshOrders, remainingLedger);
            ledgerCheck = new LedgerCheckPreview(remainingLedger, fit.requiredBuyUsd(), !fit.rejected(), fit.trimmed());
        }

        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayOrders, otherStrategiesPlannedBuyUsd, ledgerCheck);
    }
}
