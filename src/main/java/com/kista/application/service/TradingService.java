package com.kista.application.service;

import com.kista.domain.model.user.*;
import com.kista.domain.model.account.*;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.order.*;
import com.kista.domain.model.kis.*;
import com.kista.domain.model.admin.*;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static java.math.RoundingMode.HALF_UP;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradingService implements ExecuteTradingUseCase {

    private final KisHolidayPort kisHolidayPort;               // 미국 시장 개장일 확인
    private final KisAccountPort kisAccountPort;               // 계좌 잔고 조회
    private final KisPricePort kisPricePort;                   // 현재 주가 조회
    private final KisOrderPort kisOrderPort;                   // 주문 접수
    private final KisExecutionPort kisExecutionPort;           // 당일 체결 내역 조회
    private final TradingStrategy tradingStrategy;             // 매수/매도 수량·가격 계산
    private final CorrectionStrategy correctionStrategy;       // 미체결 주문 보정
    private final TradeHistoryPort tradeHistoryPort;           // 거래 이력 저장
    private final PortfolioSnapshotPort portfolioSnapshotPort; // 포트폴리오 스냅샷 저장
    private final NotifyPort notifyPort;                       // 관리자 텔레그램 알림 (오류·휴장·잔고부족)
    private final UserNotificationPort userNotificationPort;   // 사용자별 텔레그램 알림 (매매 결과)
    private final OrderPort orderPort;                         // 계획 주문 저장·조회
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 매매 알림
    private final TradingCycleHistoryRepository cycleHistoryRepository; // 사이클별 일별 스냅샷 저장

    @Override
    public void execute(TradingCycle cycle, Account account, User user) throws InterruptedException {
        execute(cycle, account, user, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void execute(TradingCycle cycle, Account account, User user, DstInfo dst) throws InterruptedException {
        LocalDate today = LocalDate.now();

        // 1. 휴장 확인 (waitForOrderTime 전으로 이동: 휴장이면 대기 없이 즉시 return)
        if (!isMarketOpen(today, account)) return;

        // 2. 잔고 조회
        AccountBalance balance = kisAccountPort.getBalance(account, cycle.ticker());
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), cycle.ticker().name(), balance.holdings(), balance.usdDeposit());
        if (balance.shouldSkip()) {
            log.info("잔고 부족 — 매매 건너뜀: [{}]", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance, cycle.ticker());
            return;
        }

        // 3. 현재가 조회
        BigDecimal price = kisPricePort.getPrice(cycle.ticker(), account);
        log.info("현재가: ${}", price);

        // 4. 전략 계산 → orders에 PLANNED 저장 (plan 단계)
        InfinitePosition position = new InfinitePosition(balance, cycle.ticker(), price, cycle.multiple());
        log.info("[{}] 전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}",
                account.nickname(), position.priceOffsetRate(), position.currentRound(), position.unitAmount());
        savePlannedOrders(position, today, account);

        // 5. 주문 시각까지 대기 (계획 저장 후 대기로 이동)
        waitForOrderTime(dst);

        // 6. orders(PLANNED) 조회 → KIS 일괄 접수 (execute 단계)
        List<Order> mainOrders = executePlannedOrders(today, account);

        // 7. 장 후 체결 확인 대기
        waitForPostClose(dst);

        // 8. 체결 내역 조회
        List<Execution> executions = kisExecutionPort.getExecutions(today, today, cycle.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 9. 보정 주문
        List<Order> corrections = applyCorrections(mainOrders, executions, today, account);

        // 10. 이력 저장 + 알림
        saveAndNotify(balance, price, today, position, mainOrders, corrections, executions, user, account, cycle);
    }

    // 전략 계산 결과를 orders에 PLANNED 상태로 저장
    private void savePlannedOrders(InfinitePosition position, LocalDate today, Account account) {
        List<Order> templates = tradingStrategy.buildOrders(position, today);
        List<Order> planned = templates.stream()
                .map(o -> Order.plan(o, account.id()))
                .toList();
        orderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PLANNED)", account.nickname(), planned.size());
    }

    // orders에서 PLANNED 조회 후 KIS에 일괄 접수, 완료 즉시 PLACED 기록
    private List<Order> executePlannedOrders(LocalDate today, Account account) {
        List<Order> planned = orderPort.findPlannedByAccountAndDate(account.id(), today);
        List<Order> placed = planned.stream().map(p -> {
            Order placedOrder = kisOrderPort.place(p, account);
            orderPort.markPlaced(p.id(), placedOrder.kisOrderId()); // 접수 완료 즉시 기록
            return placedOrder;
        }).toList();
        log.info("[{}] 주문 {}건 접수", account.nickname(), placed.size());
        return placed;
    }

    private void waitForOrderTime(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilOrderTime().toMillis();
        log.info("DST={}, 주문 시각까지 대기: {}ms", dst.isDst(), ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("주문 시각 도달");
    }

    // false 반환 시 알림 발송 후 execute()에서 즉시 return
    private boolean isMarketOpen(LocalDate today, Account account) {
        boolean open = kisHolidayPort.isMarketOpen(today, account);
        log.info("[{}] 시장 개장 여부: {}", account.nickname(), open);
        if (!open) {
            log.info("휴장일 — 매매 건너뜀");
            notifyPort.notifyMarketClosed();
        }
        return open;
    }

    private void waitForPostClose(DstInfo dst) throws InterruptedException {
        long ms = dst.waitUntilPostClose().toMillis();
        log.info("PostClose까지 대기: {}ms", ms);
        if (ms > 0) Thread.sleep(ms);
        log.info("PostClose 대기 완료");
    }

    private List<Order> applyCorrections(List<Order> mainOrders,
                                         List<Execution> executions, LocalDate today, Account account) {
        List<Order> corrections = correctionStrategy.correct(mainOrders, executions, today)
                .stream()
                .map(o -> kisOrderPort.place(o, account))
                .toList();
        log.info("[{}] 보정 주문 {}건", account.nickname(), corrections.size());
        return corrections;
    }

    private void saveAndNotify(AccountBalance balance, BigDecimal price, LocalDate today,
                               InfinitePosition position, List<Order> mainOrders,
                               List<Order> corrections, List<Execution> executions,
                               User user, Account account, TradingCycle cycle) {
        mainOrders.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
        corrections.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
        log.info("[{}] 거래 이력 {}건 저장", account.nickname(), mainOrders.size() + corrections.size());
        portfolioSnapshotPort.save(toSnapshot(balance, price, today, account, cycle.ticker()));
        log.info("[{}] 포트폴리오 스냅샷 저장 완료", account.nickname());
        saveCycleHistory(balance, cycle, today); // 사이클별 일별 스냅샷 저장
        TradingReport report = buildReport(today, position, mainOrders, corrections, executions);
        userNotificationPort.notifyTradingReport(user, account, report);
        log.info("[{}] 텔레그램 리포트 발송 완료", account.nickname());
        // 체결 건별 SSE 실시간 알림 (@Transactional 외부에서 호출 — 이미 DB 저장 완료 후)
        for (Execution e : executions) {
            TradeEvent event = e.direction() == SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), event);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), executions.size());
    }

    // execute() 종료 시 일별 1건 적재 — UNIQUE 충돌(동일 trade_date 재실행) 시 무시
    private void saveCycleHistory(AccountBalance balance, TradingCycle cycle, LocalDate today) {
        try {
            LocalDate tradeDate = today.atStartOfDay(ZoneId.of("Asia/Seoul")).toLocalDate();
            TradingCycleHistory history = new TradingCycleHistory(
                    null, cycle.id(), tradeDate,
                    balance.usdDeposit(), balance.avgPrice(),
                    BigDecimal.valueOf(balance.holdings()), null
            );
            cycleHistoryRepository.save(history);
            log.info("[{}] 거래 사이클 이력 저장 완료: cycleId={}, tradeDate={}", cycle.id(), tradeDate, today);
        } catch (DataIntegrityViolationException e) {
            log.warn("[cycleId={}] 거래 사이클 이력 중복 무시: trade_date={}", cycle.id(), today);
        }
    }

    private TradeHistory toHistory(Order o, java.util.UUID accountId) {
        BigDecimal amountUsd = o.price().multiply(BigDecimal.valueOf(o.quantity()))
                .setScale(2, HALF_UP);
        return new TradeHistory(
                null, o.tradeDate(), o.ticker(), "SOXL_DIVISION",
                o.orderType(), o.direction(), o.quantity(), o.price(),
                amountUsd, o.status(), o.kisOrderId(), accountId, null);
    }

    private PortfolioSnapshot toSnapshot(AccountBalance balance, BigDecimal price,
                                          LocalDate today, Account account, TradingCycle.Ticker ticker) {
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(balance.holdings()))
                .setScale(2, HALF_UP);
        BigDecimal totalAsset = marketValue.add(balance.usdDeposit())
                .setScale(2, HALF_UP);
        return new PortfolioSnapshot(
                null, today, ticker, balance.holdings(), balance.avgPrice(),
                price, marketValue, balance.usdDeposit(), totalAsset, account.id(), null);
    }

    private TradingReport buildReport(LocalDate today, InfinitePosition position,
                                      List<Order> mainOrders, List<Order> corrections,
                                      List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, position.toSnapshot(), mainOrders, corrections, totalBought, totalSold);
    }
}
