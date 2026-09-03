package com.kista.trading.application.service;

import com.kista.trading.application.event.TradingReportReadyEvent;
import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;
import com.kista.privacy.domain.model.PrivacyTradeBase;
import com.kista.trading.domain.model.StrategyRef; import com.kista.trading.domain.model.*;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.sharedkernel.NotificationType;
import com.kista.user.domain.model.User;
import com.kista.user.domain.model.UserSettings;
import com.kista.user.application.port.output.UserSettingsPort;
import com.kista.application.port.output.*; import com.kista.trading.application.port.output.*;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.broker.application.port.output.ExecutionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

// 체결 조회 + 알림 발송 (포지션 저장은 CyclePositionPersistor에 위임)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReporter {

    private final BrokerAdapterRegistry registry;
    private final OrderPort orderPort;                              // 주문 체결 상태 갱신
    private final UserSettingsPort userSettingsPort;                // TRADING_ALERT 알림 활성 여부 조회
    private final CyclePositionPersistor cyclePositionPersistor;   // 포지션 스냅샷 저장 위임
    private final ApplicationEventPublisher eventPublisher;        // 리포트/SSE 알림 이벤트 발행

    void recordAndNotify(LocalDate today, BatchContext ctx, AccountBalance balance,
                         BigDecimal closingPrice, List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        StrategyRef strategy = ctx.strategy();
        Account account = ctx.account();
        User user = ctx.user();
        // 장마감 후에도 체결 가능한 잔여 PLACED 주문을 취소 — 애프터마켓 체결이 CANCELLED로 오기록되는 것을 방지
        cancelUnresolvedOrders(mainOrders, account);

        // today는 KST — KIS는 어댑터에서 toUtc 변환, Toss는 KST 날짜 그대로 전달
        List<Execution> executions = registry.require(account.toBrokerRef(), ExecutionPort.class).getExecutions(today, today, strategy.ticker(), account.toBrokerRef());
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로) — broker Execution → Fill 매핑 경유
        AccountBalance postBalance = balance.applyExecutions(AccountBalance.Fill.listOf(executions));
        cyclePositionPersistor.saveCyclePosition(today, postBalance, ctx, closingPrice, privacyBase);

        // 접수된 주문별 체결 현황 기록 (FILLED / PARTIALLY_FILLED)
        markFilledOrders(mainOrders, executions);

        // TRADING_ALERT 알림 활성 여부 확인(기본값 true) 후 리포트/SSE 알림 이벤트 발행 — 실제 발송은 TradingReportNotifier에 위임
        TradingReport report = buildReport(today, strategy.type(), strategy.ticker(), executions);
        UserSettings settings = userSettingsPort.findOrDefault(user.id());
        boolean reportEnabled = settings.isNotificationEnabled(NotificationType.TRADING_ALERT);
        eventPublisher.publishEvent(new TradingReportReadyEvent(user.id(), account.id(), report, executions, reportEnabled));
        log.info("[{}] 매매 리포트 이벤트 발행 완료 (executions={}건)", account.nickname(), executions.size());
    }

    // 체결 조회 전 잔여 PLACED 주문을 증권사에 취소 요청 — 이미 체결된 주문의 취소는 브로커가 거부(무시)한다.
    // 실패해도 흐름은 계속되며(다음 getExecutions로 실제 상태를 확정), 취소 자체 실패만 관리자에게 알린다.
    // Toss 전용 — 정규장 지정가 주문이 애프터장까지 이어져 다음날 09:00 KST에야 자동 취소되므로 명시적 취소가 필요.
    // KIS는 정규장 종료 시 자동 취소되어 이 호출이 불필요 — 스킵해 마감 시점 KIS API 호출량(rate-limit 위험)도 함께 줄인다.
    private void cancelUnresolvedOrders(List<Order> mainOrders, Account account) {
        if (account.broker() != Account.Broker.TOSS) return;
        for (Order order : mainOrders) {
            if (order.status() != Order.OrderStatus.PLACED || order.externalOrderId() == null) continue;
            try {
                registry.require(account.toBrokerRef(), BrokerOrderCorrectionPort.class)
                        .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
            } catch (Exception e) {
                if (isAlreadyFilled(e)) {
                    // 취소 요청 직전/직후 체결 확정 — 브로커 주석대로 예상된 경합, 관리자 알림 불필요
                    log.info("[orderId={}] 취소 시점 이미 체결 완료 — 취소 생략", order.id());
                } else {
                    log.warn("[orderId={}] 마감 후 잔여 주문 취소 실패: {}", order.id(), e.getMessage());
                    eventPublisher.publishEvent(new TradingErrorEvent(null, e.getMessage()));
                }
            }
        }
    }

    // 취소 시점 이미 체결 확정 여부 — TossHttpClient가 409 CONFLICT(already-filled) 응답을 판정해 TossApiException에 실어 보낸다
    private boolean isAlreadyFilled(Exception e) {
        return e instanceof TossApiException tae && tae.isAlreadyFilledConflict();
    }

    // 접수 주문과 실체결 내역을 externalOrderId 기준으로 매칭하여 FILLED / PARTIALLY_FILLED 기록
    private void markFilledOrders(List<Order> mainOrders, List<Execution> executions) {
        // externalOrderId → 체결 목록 그룹핑 (1:N 체결 허용)
        Map<String, List<Execution>> byOrderId = executions.stream()
                .filter(e -> e.externalOrderId() != null && !e.externalOrderId().isBlank())
                .collect(Collectors.groupingBy(Execution::externalOrderId));

        for (Order order : mainOrders) {
            if (order.externalOrderId() == null) continue;
            List<Execution> matched = byOrderId.get(order.externalOrderId());
            if (matched == null || matched.isEmpty()) {
                // 체결 내역 없음 → 미체결(LOC/MOC 당일 자동 취소) → CANCELLED
                orderPort.markCancelled(order.id());
                log.info("[orderId={}] 미체결 → CANCELLED", order.id());
                continue;
            }

            int filledQuantity = matched.stream().mapToInt(Execution::quantity).sum();
            // 가중평균 체결가: Σ(체결금액) ÷ Σ(체결수량)
            BigDecimal totalAmt = matched.stream().map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgFilledPrice = filledQuantity > 0
                    ? totalAmt.divide(BigDecimal.valueOf(filledQuantity), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            int orderedQuantity = order.quantity() != null ? order.quantity() : 0;
            Order.OrderStatus newStatus = filledQuantity >= orderedQuantity
                    ? Order.OrderStatus.FILLED
                    : Order.OrderStatus.PARTIALLY_FILLED;

            orderPort.markFilled(order.id(), filledQuantity, avgFilledPrice, newStatus);
            log.info("[orderId={}] {} → {}, 체결수량={}/{}", order.id(), order.status(), newStatus, filledQuantity, orderedQuantity);
        }
    }

    private TradingReport buildReport(LocalDate today, StrategyType strategyType, StrategyTicker ticker, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == Direction.BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == Direction.SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, strategyType, ticker, totalBought, totalSold);
    }
}
