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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class TradingPreviewService {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final StrategyOrderPlanBuilder planBuilder;
    private final TradingBuyCompetitionSimulator competitionSimulator;

    // execute()와 동일한 잔고 출처(CyclePosition) 및 전략 분기로 미리보기
    // 휴장 여부는 무시하고 항상 강제 계산 — DB 저장 없음
    @Transactional(readOnly = true)
    NextOrdersPreview preview(UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);

        // 현재 StrategyCycle — initialUsdDeposit 조회(PRIVACY) 및 경쟁 시뮬레이션에 사용
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new NoSuchElementException("활성 사이클 없음: strategyId=" + strategy.id()));

        return buildPreview(strategy, account, currentCycle);
    }

    // 계좌 내 전략 전체를 한 번의 트랜잭션·요청으로 미리보기 — 목록 화면에서 전략 N개를 개별 호출하던 것을 1회로 축소
    // 사이클 없는 전략은 결과 맵에서 생략(단건 preview는 404지만 배치는 부분 실패를 허용) — 계좌 내 전략 수는 소규모라 O(N) 순회로 충분
    @Transactional(readOnly = true)
    Map<UUID, NextOrdersPreview> previewBatch(UUID accountId, UUID requesterId) {
        Account account = accountPort.requireOwnedAccount(accountId, requesterId);

        Map<UUID, NextOrdersPreview> previews = new LinkedHashMap<>();
        for (Strategy strategy : strategyPort.findByAccountId(accountId)) {
            strategyCyclePort.findLatestByStrategyId(strategy.id())
                    .ifPresent(cycle -> previews.put(strategy.id(), buildPreview(strategy, account, cycle)));
        }
        return previews;
    }

    private NextOrdersPreview buildPreview(Strategy strategy, Account account, StrategyCycle currentCycle) {
        // 스케쥴러는 KST 04:00에 실행 — 04:00 이후 미리보기는 내일 매매 기준
        LocalDate today = DstInfo.nextTradeDate();

        // 오늘 이미 등록된 주문 조회 — PLANNED(취소 가능) + PLACED(AT_OPEN 선접수됨) 모두 포함
        List<Order> todayOrders =
                orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);

        // 계좌 내 타 전략 당일 PLANNED BUY 합계 (이 전략 분 제외 — 예수금 부족 계산에 사용)
        BigDecimal totalAccountPlannedBuy = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        BigDecimal thisStrategyPlannedBuy = todayOrders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.BUY)
                .map(o -> o.price().multiply(BigDecimal.valueOf(o.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal otherStrategiesPlannedBuyUsd = totalAccountPlannedBuy.subtract(thisStrategyPlannedBuy);

        StrategyOrderPlanBuilder.PlanResult result =
                planBuilder.build(strategy, account, currentCycle, today, "preview:" + strategy.id());
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
                : competitionSimulator.simulate(strategy, account, currentCycle, buyOrders, today, otherStrategiesPlannedBuyUsd);

        return new NextOrdersPreview(today, plan.position(), plan.orders(), null, todayOrders, otherStrategiesPlannedBuyUsd, competition);
    }
}
