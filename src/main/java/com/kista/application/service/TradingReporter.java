package com.kista.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycleHistory;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.TradingCycleHistoryPort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;

// 체결 조회 + 사이클 이력 저장 + 텔레그램·SSE 알림 + 연속 정책 처리
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReporter {

    private final KisExecutionPort kisExecutionPort;
    private final UserNotificationPort userNotificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort;
    private final TradingCycleHistoryPort cycleHistoryPort;
    private final CycleRotationService cycleRotationService;

    void recordAndNotify(LocalDate today, TradingCycle cycle, Account account, User user,
                         AccountBalance balance, TradingSnapshot snapshot, BigDecimal closingPrice,
                         List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        List<Execution> executions = kisExecutionPort.getExecutions(today, today, cycle.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
        saveCycleHistory(postBalance, cycle, account, user, closingPrice, privacyBase); // 사이클별 스냅샷 저장

        if (snapshot != null) { // PRIVACY 전략은 스냅샷 없음 — 텔레그램 리포트 생략
            // 매매 후 상태로 스냅샷 재계산 (종가 기준 편차율·목표가 포함)
            TradingSnapshot postSnapshot = closingPrice != null
                    ? new InfinitePosition(postBalance, cycle.ticker(), closingPrice).toSnapshot()
                    : snapshot;
            TradingReport report = buildReport(today, postSnapshot, mainOrders, executions);
            userNotificationPort.notifyTradingReport(user, account, report);
            log.info("[{}] 텔레그램 리포트 발송 완료", account.nickname());
        }
        // 체결 건별 SSE 실시간 알림 (@Transactional 외부에서 호출 — 이미 DB 저장 완료 후)
        for (Execution e : executions) {
            TradeEvent event = e.direction() == SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), event);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), executions.size());
    }

    // execute() 종료 시 1건 적재, 사이클 종료 시 연속 정책 처리
    private void saveCycleHistory(AccountBalance balance, TradingCycle cycle,
                                  Account account, User user, BigDecimal price, PrivacyTradeBase privacyBase) {
        TradingCycleHistory history = TradingCycleHistory.tradeSnapshot(cycle.id(), balance, price);
        cycleHistoryPort.save(history);
        log.info("[cycleId={}] 거래 사이클 이력 저장 완료", cycle.id());

        if (history.holdings() == 0 && cycle.cycleSeedType().isConsecutive()) {
            log.info("[cycleId={}] 사이클 종료 — 연속 정책 실행: {}", cycle.id(), cycle.cycleSeedType());
            cycleRotationService.rotate(cycle, account, user, price, privacyBase);
        } else if (history.holdings() == 0) {
            log.info("[cycleId={}] 사이클 종료 (연속 없음)", cycle.id());
        }
    }

    private TradingReport buildReport(LocalDate today, TradingSnapshot snapshot,
                                      List<Order> mainOrders, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, snapshot, mainOrders, totalBought, totalSold);
    }
}
