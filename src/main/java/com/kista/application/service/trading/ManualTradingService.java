package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.kista.domain.port.out.KisPricePort.PriceSnapshot;

import java.math.BigDecimal;
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
    private final NotifyPort notifyPort;
    private final TradingPriceFetcher priceFetcher;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderComputer orderComputer;
    private final TradingOrderPlanner orderPlanner;

    List<Order> execute(UUID strategyId, UUID requesterId) {
        // 동기 검증: 소유권·타입·상태
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        Account account = accountPort.requireOwnedAccount(strategy.accountId(), requesterId);
        if (strategy.type() != Strategy.Type.INFINITE)
            throw new IllegalArgumentException("INFINITE 전략만 수동 실행 가능합니다");
        if (strategy.status() != Strategy.Status.ACTIVE)
            throw new IllegalArgumentException("ACTIVE 상태의 전략만 수동 실행 가능합니다");

        // 현재 StrategyCycle 조회 — initialUsdDeposit 필요
        StrategyCycle currentCycle = strategyCyclePort.findLatestByStrategyId(strategy.id())
                .orElseThrow(() -> new IllegalStateException("활성 사이클 없음: strategyId=" + strategy.id()));

        // 주문 가능 시간대 확인 — BLOCKED(DST 05:00~17:00, 비DST 06:00~18:00)면 즉시 거부
        DstInfo dst = DstInfo.immediate();
        if (dst.currentSession() == DstInfo.MarketSession.BLOCKED) {
            String range = dst.isDst() ? "05:00~17:00" : "06:00~18:00";
            throw new ManualTradingException("주문 불가 시간대입니다 (KST " + range + ")");
        }

        // 스케줄러와 동일 today 계산: KST 04:00 이후면 +1일(= 다음 US 거래일)
        LocalDate today = DstInfo.nextTradeDate();

        // 이중 실행 방지 — PLANNED 또는 PLACED 중 하나라도 있으면 거부
        if (!orderPort.findPlannedOrPlacedByAccountAndDate(account.id(), today).isEmpty())
            throw new ManualTradingException("오늘 이미 주문이 등록된 전략입니다");

        User user = userPort.findByIdOrThrow(account.userId());

        // 전일종가 조회(0회차 평단가 대용) 후 PLANNED 주문 저장 — KIS 접수는 스케줄러가 담당
        Map<Strategy.Ticker, PriceSnapshot> snapshots = priceFetcher.fetchPriceSnapshots(
                List.of(strategy.ticker()), account);
        PriceSnapshot priceSnapshot = snapshots.get(strategy.ticker());
        BigDecimal prevClosePrice = priceSnapshot != null ? priceSnapshot.prevClose() : null;

        AccountBalance balance = balanceLoader.loadBalanceOrThrow(strategy).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), strategy.ticker().name(), balance.holdings(), balance.usdDeposit());

        // INFINITE는 initialUsdDeposit null 전달 (PlanContext에서 무시됨)
        CycleOrderComputer.ComputeResult result = orderComputer.compute(
                balance, strategy, prevClosePrice, today, null, null, account.nickname());
        if (result.isSkipped()) return List.of(); // 전략 차원 skip (기준매매표 미수신 등)

        if (!result.valid()) {
            log.warn("[{}] 주문 유효성 실패 — 잔액 부족 또는 보유수량 초과", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance, strategy.ticker());
            return List.of();
        }

        orderPlanner.savePlannedOrders(result.orders(), account);

        // 저장된 PLANNED 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedByAccountAndDate(account.id(), today);
    }
}
