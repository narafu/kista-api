package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.common.CycleLookups;
import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.Order;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.StrategyRef; import com.kista.trading.domain.model.*;
import com.kista.user.domain.model.User;
import com.kista.user.application.port.output.UserPort;
import com.kista.privacy.application.port.output.PrivacyTradePort; import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import com.kista.trading.application.event.TradingErrorEvent;
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
import com.kista.sharedkernel.StrategyTicker;

@Slf4j
@Service
@RequiredArgsConstructor
class ManualTradingService {

    private final StrategyLookupPort strategyPort;
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
    private final ApplicationEventPublisher eventPublisher; // live 잔고 조회 실패 시 관리자 알림 이벤트 (4xx라 GlobalExceptionHandler가 미기록)

    List<Order> execute(UUID strategyId, UUID requesterId) {
        return execute(strategyId, requesterId, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 개장 여부를 결정론적으로 고정
    List<Order> execute(UUID strategyId, UUID requesterId, DstInfo dst) {
        // 동기 검증: 소유권·상태
        StrategyRef strategy = strategyPort.findByIdOrThrow(strategyId);
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
        // PrivacyTradePort에는 이 조합 전용 헬퍼가 없어 동일 로직을 인라인
        PrivacyTradeBase privacyBase = strategy.isPrivacy() ? privacyTradePort.findTodayTrade(today).orElse(null) : null;

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

        // 보유수량 부족 체크: 기존 예약 SELL + 신규 SELL > 판매가능수량 (KIS: CTRP6504R / Toss: /api/v1/sellable-quantity)
        checkSellableOrThrow(account, strategy, today, plan.orders());

        orderPlanner.savePlannedOrders(plan.orders(), account, currentCycle.id());

        // 개장 이후 수동 실행 시 AT_OPEN 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
        // plan.position()/plan.vrPosition() — BUY cap 보정(orderExecutor.placeAtOpenOrders)에 필요
        placeAtOpenOrdersIfMarketOpen(strategy, account, currentCycle.id(), today, plan.position(), plan.vrPosition(), dst);

        // 저장된 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedOrPlacedByCycleAndDate(currentCycle.id(), today);
    }

    // 시세 조회 실패 시 ManualTradingException으로 래핑
    private BigDecimal fetchPrevCloseOrThrow(StrategyRef strategy, Account account) {
        try {
            Map<StrategyTicker, PriceSnapshot> snapshots =
                    priceFetcher.fetchPriceSnapshots(List.of(strategy.ticker()), account);
            return PriceSnapshot.prevCloseOrNull(snapshots.get(strategy.ticker()));
        } catch (Exception e) {
            // priceFetcher.fetchPriceSnapshots는 내부에서 실패를 흡수하고 절대 던지지 않으므로(TradingPriceFetcher가 자체 notifyError 처리)
            // 이 catch는 도달하지 않음 — 인터페이스 계약 방어용으로만 유지
            log.warn("종가 조회 실패 — 바로주문 중단: ticker={}, error={}", strategy.ticker().name(), e.getMessage());
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }

    // live 잔고 조회 실패 시 ManualTradingException으로 래핑
    private AccountBalance fetchLiveBalanceOrThrow(Account account, StrategyRef strategy) {
        try {
            BrokerBalance bb = registry.require(account.toBrokerRef(), LiveBalancePort.class).getLiveBalance(account.toBrokerRef(), strategy.ticker());
            AccountBalance lb = new AccountBalance(bb.holdings(), bb.avgPrice(), bb.usdDeposit());
            log.info("live 잔고 조회: [{}] {} holdings={}주, usdDeposit=${}",
                    account.nickname(), strategy.ticker().name(), lb.holdings(), lb.usdDeposit());
            return lb;
        } catch (Exception e) {
            log.warn("live 잔고 조회 실패 — 바로주문 중단: account={}, ticker={}, error={}",
                    account.id(), strategy.ticker().name(), e.getMessage());
            // 4xx(ManualTradingException)는 GlobalExceptionHandler가 app_error_logs에 남기지 않으므로 여기서 직접 기록
            eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
            throw new ManualTradingException("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요", e);
        }
    }

    // 기존 예약 SELL과 신규 SELL 합계가 판매가능수량을 초과하면 ManualTradingException
    private void checkSellableOrThrow(Account account, StrategyRef strategy, LocalDate tradeDate, List<Order> orders) {
        int newSellTotal = orders.stream()
                .filter(o -> o.direction() == Order.OrderDirection.SELL)
                .mapToInt(Order::quantity).sum();
        int sellableQty = registry.require(account.toBrokerRef(), SellableQuantityPort.class).getSellableQuantity(strategy.ticker(), account.toBrokerRef()).quantity();
        int reservedSellTotal = orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, strategy.ticker());
        log.info("SELL 수량 검증: [{}] {} 예약={}주, 신규={}주, 판매가능={}주",
                account.nickname(), strategy.ticker().name(), reservedSellTotal, newSellTotal, sellableQty);
        if (reservedSellTotal + newSellTotal > sellableQty) {
            throw new ManualTradingException("보유 수량이 부족합니다");
        }
    }

    // 개장 이후 수동 실행 시 AT_OPEN 주문 즉시 접수 (개장 전이면 개장 스케쥴러가 담당)
    // INFINITE: AT_OPEN 매도 선접수 / VR: AT_OPEN 매수·매도 사다리 즉시 접수 (BUY cap 보정 포함)
    // PRIVACY: AT_OPEN 주문 없으므로 자연 no-op
    // dst는 execute()에서 주입 — 단위 테스트에서 개장 전/후 분기를 결정론적으로 고정하기 위함
    private void placeAtOpenOrdersIfMarketOpen(StrategyRef strategy, Account account, UUID cycleId, LocalDate today,
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
