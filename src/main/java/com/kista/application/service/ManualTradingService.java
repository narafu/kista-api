package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.ManualTradingException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.DstInfo;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.ManualExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.domain.strategy.CycleOrderStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class ManualTradingService implements ManualExecuteTradingUseCase {

    private final TradingCyclePort cyclePort;
    private final AccountPort accountPort;
    private final OrderPort orderPort;
    private final UserPort userPort;
    private final NotifyPort notifyPort;
    private final TradingPriceFetcher priceFetcher;
    private final TradingBalanceLoader balanceLoader;
    private final CycleOrderStrategies cycleStrategies;
    private final TradingOrderPlanner orderPlanner;

    @Override
    public List<Order> execute(UUID cycleId, UUID requesterId) {
        // 동기 검증: 소유권·타입·상태
        TradingCycle cycle = cyclePort.findByIdOrThrow(cycleId);
        Account account = accountPort.findByIdOrThrow(cycle.accountId());
        account.verifyOwnedBy(requesterId); // SecurityException → 403
        if (cycle.type() != TradingCycle.Type.INFINITE)
            throw new IllegalArgumentException("INFINITE 사이클만 수동 실행 가능합니다");
        if (cycle.status() != TradingCycle.Status.ACTIVE)
            throw new IllegalArgumentException("ACTIVE 상태의 사이클만 수동 실행 가능합니다");

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
            throw new ManualTradingException("오늘 이미 주문이 등록된 사이클입니다");

        User user = userPort.findByIdOrThrow(account.userId());

        // 현재가 조회 후 PLANNED 주문 저장 — KIS 접수는 스케줄러가 담당
        Map<TradingCycle.Ticker, BigDecimal> prices = priceFetcher.fetchPrices(
                List.of(cycle.ticker()), account);
        BigDecimal price = prices.get(cycle.ticker());

        AccountBalance balance = balanceLoader.loadBalanceOrThrow(cycle).balance();
        log.info("잔고 조회 (이력): [{}] {} {}주, 통합주문가능금액 ${}",
                account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());

        CycleOrderStrategy strategy = cycleStrategies.of(cycle);
        var planOpt = strategy.plan(new CycleOrderStrategy.PlanContext(
                balance, cycle, price, today, null, account.nickname()));
        if (planOpt.isEmpty()) return List.of(); // 전략 차원 skip (기준매매표 미수신 등)

        List<Order> orders = planOpt.get().orders();
        if (!balance.isOrderValid(orders)) {
            log.warn("[{}] 주문 유효성 실패 — 잔액 부족 또는 보유수량 초과", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
            return List.of();
        }

        orderPlanner.savePlannedOrders(orders, account);

        // 저장된 PLANNED 주문 반환 (UI에서 예약 확인용)
        return orderPort.findPlannedByAccountAndDate(account.id(), today);
    }
}
