package com.kista.application.service;

import com.kista.domain.model.*;
import com.kista.domain.port.in.ExecuteTradingUseCase;
import com.kista.domain.port.out.*;
import com.kista.domain.strategy.CorrectionStrategy;
import com.kista.domain.strategy.TradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.Order.OrderDirection.BUY;
import static com.kista.domain.model.Order.OrderDirection.SELL;
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
    private final PlannedOrderPort plannedOrderPort;           // 계획 주문 저장·조회

    @Override
    public void execute(Account account, User user) throws InterruptedException {
        execute(account, user, DstInfo.calculate());
    }

    // package-private: DstInfo 주입으로 단위 테스트에서 sleep 우회
    void execute(Account account, User user, DstInfo dst) throws InterruptedException {
        LocalDate today = LocalDate.now();

        // 1. 휴장 확인 (waitForOrderTime 전으로 이동: 휴장이면 대기 없이 즉시 return)
        if (!isMarketOpen(today, account)) return;

        // 2. 잔고 조회
        AccountBalance balance = kisAccountPort.getBalance(account);
        log.info("잔고 조회: [{}] {} {}주, 통합주문가능금액 ${}", account.nickname(), account.symbol(), balance.quantity(), balance.usdDeposit());
        if (balance.shouldSkip()) {
            log.info("잔고 부족 — 매매 건너뜀: [{}]", account.nickname());
            notifyPort.notifyInsufficientBalance(account, balance);
            return;
        }

        // 3. 현재가 조회
        BigDecimal price = kisPricePort.getPrice(account.symbol(), account);
        log.info("현재가: ${}", price);

        // 4. 전략 계산 → planned_orders에 저장 (plan 단계)
        TradingVariables vars = tradingStrategy.calculate(balance, price);
        log.info("[{}] 전략 계산: priceOffsetRate={}, currentRound={}, unitAmount={}",
                account.nickname(), vars.priceOffsetRate(), vars.currentRound(), vars.unitAmount());
        savePlannedOrders(vars, today, account);

        // 5. 주문 시각까지 대기 (계획 저장 후 대기로 이동)
        waitForOrderTime(dst);

        // 6. planned_orders 조회 → KIS 일괄 접수 (execute 단계)
        List<Order> mainOrders = executePlannedOrders(today, account);

        // 7. 장 후 체결 확인 대기
        waitForPostClose(dst);

        // 8. 체결 내역 조회
        List<Execution> executions = kisExecutionPort.getExecutions(today, account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 9. 보정 주문
        List<Order> corrections = applyCorrections(mainOrders, executions, today, account);

        // 10. 이력 저장 + 알림
        saveAndNotify(balance, price, today, vars, mainOrders, corrections, executions, user, account);
    }

    // 전략 계산 결과를 planned_orders에 PENDING 상태로 저장
    private void savePlannedOrders(TradingVariables vars, LocalDate today, Account account) {
        List<Order> pending = tradingStrategy.buildOrders(vars, today, account.symbol());
        List<PlannedOrder> planned = pending.stream()
                .map(o -> PlannedOrder.from(o, account.id()))
                .toList();
        plannedOrderPort.saveAll(planned);
        log.info("[{}] 계획 주문 {}건 저장 (PENDING)", account.nickname(), planned.size());
    }

    // planned_orders에서 PENDING 조회 후 KIS에 일괄 접수, 완료 즉시 EXECUTED 기록
    private List<Order> executePlannedOrders(LocalDate today, Account account) {
        List<PlannedOrder> pending = plannedOrderPort.findPendingByAccountAndDate(account.id(), today);
        List<Order> placed = pending.stream().map(p -> {
            Order placedOrder = kisOrderPort.place(p.toOrder(), account);
            plannedOrderPort.markExecuted(p.id(), placedOrder.kisOrderId()); // 접수 완료 즉시 기록
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
                               TradingVariables vars, List<Order> mainOrders,
                               List<Order> corrections, List<Execution> executions,
                               User user, Account account) {
        mainOrders.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
        corrections.forEach(o -> tradeHistoryPort.save(toHistory(o, account.id())));
        log.info("[{}] 거래 이력 {}건 저장", account.nickname(), mainOrders.size() + corrections.size());
        portfolioSnapshotPort.save(toSnapshot(balance, price, today, account));
        log.info("[{}] 포트폴리오 스냅샷 저장 완료", account.nickname());
        TradingReport report = buildReport(today, vars, mainOrders, corrections, executions);
        userNotificationPort.notifyTradingReport(user, account, report);
        log.info("[{}] 텔레그램 리포트 발송 완료", account.nickname());
    }

    private TradeHistory toHistory(Order o, java.util.UUID accountId) {
        BigDecimal amountUsd = o.price().multiply(BigDecimal.valueOf(o.qty()))
                .setScale(2, HALF_UP);
        return new TradeHistory(
                null, o.tradeDate(), o.symbol(), "SOXL_DIVISION",
                o.orderType(), o.direction(), o.qty(), o.price(),
                amountUsd, o.status(), o.kisOrderId(), accountId, null);
    }

    private PortfolioSnapshot toSnapshot(AccountBalance balance, BigDecimal price,
                                          LocalDate today, Account account) {
        BigDecimal marketValue = price.multiply(BigDecimal.valueOf(balance.quantity()))
                .setScale(2, HALF_UP);
        BigDecimal totalAsset = marketValue.add(balance.usdDeposit())
                .setScale(2, HALF_UP);
        return new PortfolioSnapshot(
                null, today, account.symbol(), balance.quantity(), balance.avgPrice(),
                price, marketValue, balance.usdDeposit(), totalAsset, account.id(), null);
    }

    private TradingReport buildReport(LocalDate today, TradingVariables vars,
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
        return new TradingReport(today, vars, mainOrders, corrections, totalBought, totalSold);
    }
}
