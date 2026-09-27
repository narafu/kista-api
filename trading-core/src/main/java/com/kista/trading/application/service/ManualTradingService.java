package com.kista.trading.application.service;
import com.kista.trading.application.service.support.TradingOrderPlanner;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.*;
import com.kista.trading.domain.model.NextOrdersPreview.SkipReason;
import com.kista.matching.domain.model.*;
import com.kista.trading.application.port.output.*;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.sharedkernel.TradingErrorEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class ManualTradingService {

    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final TradingPriceFetcher priceFetcher;
    private final StrategyOrderPlanBuilder planBuilder;
    private final BuyOrderPriceCapper priceCapper;
    private final TradingOrderBudgetAllocator budgetAllocator;
    private final TradingOrderPlanner orderPlanner;            // allocator 승인 결과 PLANNED 저장 — TradingCandidatePlanner와 동일 패턴(브리핑 필드 목록 누락분, 배치와 동일하게 재주입)
    private final TradingOrderExecutor orderExecutor;
    private final ApplicationEventPublisher eventPublisher; // live 잔고 조회 실패 시 관리자 알림 이벤트 (4xx라 GlobalExceptionHandler가 미기록)

    List<Order> execute(UUID strategyId, UUID requesterId) {
        return execute(strategyId, requesterId, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 개장 여부를 결정론적으로 고정
    List<Order> execute(UUID strategyId, UUID requesterId, DstInfo dst) {
        // 동기 검증: 소유권·상태
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isActive())
            throw new IllegalArgumentException("ACTIVE 상태의 전략만 수동 실행 가능합니다");

        // 현재 StrategyCycle 조회 — initialUsdDeposit 필요
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());

        // 스케쥴러와 동일 today 계산: KST 04:00 이후면 +1일(= 다음 US 거래일)
        LocalDate today = DstInfo.nextTradeDate();

        // 이중 실행 방지 — PLANNED 또는 PLACED 중 하나라도 있으면 거부
        if (!orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today).isEmpty())
            throw new ManualTradingException("오늘 이미 주문이 등록된 전략입니다");

        // 잔고 로드~전일종가~privacyBase~전략 계산을 배치와 동일한 StrategyOrderPlanBuilder에 위임한다
        // (전일종가 조회는 내부적으로 BrokerCallGuard.wrap 경유 브로커 호출이라 실패 시 raw 예외가 나올 수 있음 — 아래서 동일 패턴으로 흡수)
        StrategyOrderPlanBuilder.PlanResult result;
        try {
            result = planBuilder.build(strategy, account, currentCycle, today, account.nickname(), Map.of());
        } catch (Exception e) {
            log.warn("[{}] 계획 계산 실패 — 바로주문 중단: account={}, ticker={}, error={}",
                    account.nickname(), account.id(), strategy.ticker().name(), e.getMessage());
            // 4xx(ManualTradingException)는 GlobalExceptionHandler가 app_error_logs에 남기지 않으므로 여기서 직접 기록
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
        if (result.isSkip()) {
            // NO_CYCLE_HISTORY(사이클 이력 없음)는 데이터 무결성 오류에 준하므로 조용한 무동작이 아닌 시끄러운 실패로 승격 —
            // 그 외(PRIVACY 기준매매표 미수신 등)는 pre-Task3 ManualTradingService도 조용히 스킵했으므로 회귀 아님, 그대로 유지
            if (result.skipReason() == SkipReason.NO_CYCLE_HISTORY) {
                throw new ManualTradingException("전략 실행 이력이 없어 수동 실행할 수 없습니다");
            }
            return List.of();
        }
        CycleOrderStrategy.OrderPlan plan = result.plan();

        // 접수 전 가격 캡 적용 — 배치(TradingCandidatePlanner)와 동일 지점에서 동일 기준으로 적용해
        // 캡 적용 전 금액으로 검증하던 기존 버그(배치는 캡 적용 후 배정, 수동실행은 캡 적용 전 검증)를 없앤다
        BigDecimal startPrice = fetchStartPriceOrNull(strategy, account);
        List<PlannedOrder> preparedOrders = priceCapper.prepareForAllocation(
                plan.orders(), startPrice, plan.position(), plan.vrPosition(), strategy.ticker(),
                strategy.type(), today);

        // 예산 배정기로 예수금/보유수량 검증 — 단건 candidate 하나만 넘긴다(Task 2가 단일계좌 전용으로 축소한 진입점)
        BatchContext ctx = new BatchContext(strategy, currentCycle, account,
                null /* userProfile: 알림 미사용 경로라 null — allocate()는 approved/rejected 판단에만 ctx.account() 사용 */);
        TradingOrderBudgetAllocator.Allocation allocation;
        try {
            allocation = budgetAllocator.allocate(
                    List.of(new TradingOrderBudgetAllocator.Candidate(ctx, preparedOrders)), today);
        } catch (Exception e) {
            log.warn("[{}] 예산 배정 조회 실패 — 바로주문 중단: account={}, ticker={}, error={}",
                    account.nickname(), account.id(), strategy.ticker().name(), e.getMessage());
            // 4xx(ManualTradingException)는 GlobalExceptionHandler가 app_error_logs에 남기지 않으므로 여기서 직접 기록
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
        if (!allocation.rejectedBuy().isEmpty()) throw new ManualTradingException("예수금이 부족합니다");
        if (!allocation.rejectedSell().isEmpty()) throw new ManualTradingException("보유 수량이 부족합니다");

        List<PlannedOrder> approvedOrders = allocation.approved().stream()
                .flatMap(candidate -> candidate.orders().stream())
                .toList();
        orderPlanner.savePlannedOrders(approvedOrders, account, currentCycle.id());

        // 개장 이후 수동 실행 시 AT_OPEN 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
        // plan.position()/plan.vrPosition() — BUY cap 보정(orderExecutor.placeAtOpenOrders)에 필요
        placeAtOpenOrdersIfMarketOpen(strategy, account, currentCycle.id(), today, plan.position(), plan.vrPosition(), dst);

        // 저장된 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
    }

    // BUY 가격 캡 판단용 현재가 — 조회 실패 시 캡 미적용(null이면 prepareForAllocation이 원본 그대로 반환)
    private BigDecimal fetchStartPriceOrNull(Strategy strategy, Account account) {
        try {
            return priceFetcher.fetchPrices(List.of(strategy.ticker()), account).get(strategy.ticker());
        } catch (Exception e) {
            log.warn("[{}] 캡 판단용 현재가 조회 실패 — 캡 미적용: {}", account.nickname(), e.getMessage());
            return null;
        }
    }

    // 개장 이후 수동 실행 시 AT_OPEN 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
    // INFINITE: AT_OPEN 매도 선접수 / VR: AT_OPEN 매수·매도 사다리 즉시 접수 (BUY cap 보정 포함)
    // PRIVACY: AT_OPEN 주문 없으므로 자연 no-op
    // dst는 execute()에서 주입 — 단위 테스트에서 개장 전/후 분기를 결정론적으로 고정하기 위함
    private void placeAtOpenOrdersIfMarketOpen(Strategy strategy, Account account, UUID cycleId, LocalDate today,
                                               InfinitePosition position, VrPosition vrPosition, DstInfo dst) {
        if (Instant.now().isAfter(dst.marketOpen())) {
            // AT_OPEN 주문이 없으면(PRIVACY는 항상, INFINITE도 흔함) 불필요한 라이브 시세 조회를 건너뛴다
            if (orderPort.findAtOpenPlannedByCycleAndDate(cycleId, today).isEmpty()) return;
            // BUY cap 판단용 최신 현재가 재조회 — 단일 전략 수동 실행이라 ticker 1개(배치 불필요)
            BigDecimal currentPrice = priceFetcher.fetchPrices(List.of(strategy.ticker()), account).get(strategy.ticker());
            log.info("[{}] 개장 후 수동 실행 — AT_OPEN 주문 접수", account.nickname());
            orderExecutor.placeAtOpenOrders(today, account, cycleId, currentPrice, position, vrPosition, strategy);
        }
    }
}
