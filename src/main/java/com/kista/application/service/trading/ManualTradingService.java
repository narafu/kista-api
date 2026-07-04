package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.common.CycleLookups;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final UserPort userPort;
    private final PrivacyTradePort privacyTradePort;
    private final TradingPriceFetcher priceFetcher;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;
    private final TradingOrderExecutor orderExecutor;
    private final BrokerAdapterRegistry registry;

    List<Order> execute(UUID strategyId, UUID requesterId) {
        // 동기 검증: 소유권·상태
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (!strategy.isActive())
            throw new IllegalArgumentException("ACTIVE 상태의 전략만 수동 실행 가능합니다");

        // 현재 StrategyCycle 조회 — initialUsdDeposit 필요
        StrategyCycle currentCycle = CycleLookups.requireLatestCycle(strategyCyclePort, strategy.id());

        // 스케쥴러와 동일 today 계산: KST 04:00 이후면 +1일(= 다음 US 거래일)
        LocalDate today = DstInfo.nextTradeDate();

        // 이중 실행 방지 — PLANNED 또는 PLACED 중 하나라도 있으면 거부
        if (!orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today).isEmpty())
            throw new ManualTradingException("오늘 이미 주문이 등록된 전략입니다");

        User user = userPort.findByIdOrThrow(account.userId());

        // 전일종가 조회(0회차 평단가 대용) 후 PLANNED 주문 저장 — 증권사 접수는 스케쥴러가 담당
        BigDecimal prevClosePrice = fetchPrevCloseOrThrow(strategy, account);

        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());

        // PRIVACY는 당일 기준매매표 조회, INFINITE는 null (PlanContext에서 무시됨)
        PrivacyTradeBase privacyBase = privacyTradePort.findBaseIfPrivacy(strategy, today);

        CycleOrderStrategy.OrderPlan plan = orderComputer.compute(
                balance, strategy, prevClosePrice, today, currentCycle, privacyBase, account.nickname(), null)
                .orElse(null);
        if (plan == null) return List.of(); // 전략 차원 skip (PRIVACY 기준매매표 미수신 등)

        // live 잔고 1회 조회 — BUY 예수금·SELL 보유수량 모두 검사
        AccountBalance liveBalance = fetchLiveBalanceOrThrow(account, strategy);

        // 예수금 부족 체크: 신규 BUY 합계 > (live 잔고 - 타 전략 당일 PLANNED BUY 합계)
        BigDecimal otherBuyTotal = orderPort.sumPlannedBuyByAccountAndDate(account.id(), today);
        if (!liveBalance.hasSufficientDepositFor(plan.orders(), otherBuyTotal)) {
            throw new ManualTradingException("예수금이 부족합니다");
        }

        // 보유수량 부족 체크: SELL 수량 합계 > 판매가능수량 (KIS: CTRP6504R / Toss: /api/v1/sellable-quantity)
        checkSellableOrThrow(account, strategy, plan.orders());

        orderPlanner.savePlannedOrders(plan.orders(), account, currentCycle.id());

        // 개장 이후 수동 실행 시 INFINITE AT_OPEN 매도 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
        placeAtOpenSellsIfMarketOpen(strategy, account, currentCycle.id(), today);

        // 저장된 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
    }

    // 시세 조회 실패 시 ManualTradingException으로 래핑
    private BigDecimal fetchPrevCloseOrThrow(Strategy strategy, Account account) {
        try {
            Map<Strategy.Ticker, PriceSnapshot> snapshots =
                    priceFetcher.fetchPriceSnapshots(List.of(strategy.ticker()), account);
            return PriceSnapshot.prevCloseOrNull(snapshots.get(strategy.ticker()));
        } catch (Exception e) {
            log.warn("종가 조회 실패 — 바로주문 중단: ticker={}, error={}", strategy.ticker().name(), e.getMessage());
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }

    // live 잔고 조회 실패 시 ManualTradingException으로 래핑
    private AccountBalance fetchLiveBalanceOrThrow(Account account, Strategy strategy) {
        try {
            AccountBalance lb = registry.require(account, LiveBalancePort.class).getLiveBalance(account, strategy.ticker());
            log.info("live 잔고 조회: [{}] {} holdings={}주, usdDeposit=${}",
                    account.nickname(), strategy.ticker().name(), lb.holdings(), lb.usdDeposit());
            return lb;
        } catch (Exception e) {
            log.warn("live 잔고 조회 실패 — 바로주문 중단: account={}, ticker={}, error={}",
                    account.id(), strategy.ticker().name(), e.getMessage());
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }

    // SELL 수량 합계 > 판매가능수량이면 ManualTradingException
    private void checkSellableOrThrow(Account account, Strategy strategy, List<Order> orders) {
        int newSellTotal = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.SELL)
                .mapToInt(Order::quantity).sum();
        int sellableQty = registry.require(account, SellableQuantityPort.class).getSellableQuantity(strategy.ticker(), account).quantity();
        log.info("SELL 수량 검증: [{}] {} 계획={}주, 판매가능={}주",
                account.nickname(), strategy.ticker().name(), newSellTotal, sellableQty);
        if (newSellTotal > sellableQty) {
            throw new ManualTradingException("보유 수량이 부족합니다");
        }
    }

    // 개장 이후 수동 실행 시 INFINITE AT_OPEN 매도 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
    private void placeAtOpenSellsIfMarketOpen(Strategy strategy, Account account, UUID cycleId, LocalDate today) {
        DstInfo dst = DstInfo.calculate();
        if (strategy.isInfinite() && Instant.now().isAfter(dst.marketOpen())) {
            List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(cycleId, today);
            if (!atOpenOrders.isEmpty()) {
                log.info("[{}] 개장 후 수동 실행 — AT_OPEN 매도 {}건 즉시 접수", account.nickname(), atOpenOrders.size());
                orderExecutor.placeGiven(atOpenOrders, account);
            }
        }
    }
}
