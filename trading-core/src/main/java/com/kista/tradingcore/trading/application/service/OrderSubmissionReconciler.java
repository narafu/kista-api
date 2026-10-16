package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderStatus;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.trading.domain.model.SubmittingOrder;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 잔류 SUBMITTING 대사 — 접수 경로(배치·재개·수동 실행) 진입 시 호출. 취소 경로에서는 호출하지 않는다(취소할 주문을 새로 접수할 수 있어서)
// Toss만 선점 후 2~9분 창에서 같은 멱등키(clientOrderId)로 재요청해 원 결과를 회수한다 — KIS·창 밖은 관리자 알림 후 런북 정정
@Component
@Slf4j
class OrderSubmissionReconciler {

    static final Duration IN_FLIGHT_GRACE = Duration.ofMinutes(2); // 이보다 젊으면 다른 스레드가 접수 중일 수 있다
    static final Duration TOSS_RESEND_LIMIT = Duration.ofMinutes(9); // Toss 멱등키 유효 10분 - 시계 차이 여유 1분

    private final OrderPort orderPort;
    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock; // 선점 경과 판정용 — 테스트에서 고정 Clock 주입

    @Autowired
    OrderSubmissionReconciler(OrderPort orderPort, BrokerOrderCorrectionPort brokerOrderCorrectionPort,
                              ApplicationEventPublisher eventPublisher) {
        this(orderPort, brokerOrderCorrectionPort, eventPublisher, Clock.systemUTC());
    }

    OrderSubmissionReconciler(OrderPort orderPort, BrokerOrderCorrectionPort brokerOrderCorrectionPort,
                              ApplicationEventPublisher eventPublisher, Clock clock) {
        this.orderPort = orderPort;
        this.brokerOrderCorrectionPort = brokerOrderCorrectionPort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    void reconcile(UUID strategyCycleId, LocalDate tradeDate, TradingAccount account) {
        List<SubmittingOrder> leftovers = orderPort.findSubmittingByCycleAndDate(strategyCycleId, tradeDate);
        if (leftovers.isEmpty()) return;

        Instant now = clock.instant();
        List<UUID> unresolved = new ArrayList<>();
        for (SubmittingOrder leftover : leftovers) {
            Duration age = Duration.between(leftover.submittedAt(), now);
            if (age.compareTo(IN_FLIGHT_GRACE) < 0) continue;
            boolean resendable = account.broker() == Broker.TOSS && age.compareTo(TOSS_RESEND_LIMIT) < 0;
            if (!resendable || !resend(leftover.order(), tradeDate, account)) {
                unresolved.add(leftover.order().id());
            }
        }
        if (!unresolved.isEmpty()) {
            log.error("[{}] 접수 결과 불명 주문 잔류 — 재접수하지 않음: {}", account.nickname(), unresolved);
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[접수 결과 불명] %s 거래일 %s SUBMITTING 잔류 orderId=%s — 자동 재접수되지 않음, 증권사 확인 후 런북 정정"
                            .formatted(account.nickname(), tradeDate, unresolved)));
        }
    }

    // 같은 멱등키로 재요청 — 해소(PLACED·FAILED)되면 true, 여전히 불명이면 false
    private boolean resend(Order order, LocalDate tradeDate, TradingAccount account) {
        // 재확인: 그 사이 다른 경로가 해소했거나 거래일이 다르면 건드리지 않는다
        Order latest = orderPort.findById(order.id()).orElse(null);
        if (latest == null || latest.status() != OrderStatus.SUBMITTING || !latest.tradeDate().equals(tradeDate)) {
            return true;
        }
        OrderResult result;
        try {
            result = brokerOrderCorrectionPort.place(OrderSubmitter.instructionOf(latest), account.brokerRef());
        } catch (Exception e) {
            if (!BrokerApiException.isRejection(e)) {
                log.warn("[{}] SUBMITTING 대사 재요청 결과 불명 (orderId={}): {}", account.nickname(), order.id(), e.getMessage());
                return false;
            }
            log.warn("[{}] SUBMITTING 대사 재요청 확정 거절 — FAILED (orderId={}): {}", account.nickname(), order.id(), e.getMessage());
            return markResolved(() -> orderPort.markFailed(order.id()));
        }
        log.info("[{}] SUBMITTING 대사 — 원 주문번호 회수로 PLACED (orderId={}, externalOrderId={})",
                account.nickname(), order.id(), result.externalOrderId());
        return markResolved(() -> orderPort.markPlaced(order.id(), result.externalOrderId()));
    }

    // 상태 기록 — 그 사이 다른 경로가 해소했으면(경합) 해소된 것으로 본다, 그 외 DB 오류는 불명 유지
    private boolean markResolved(Runnable write) {
        try {
            write.run();
            return true;
        } catch (OrderStateConflictException e) {
            return true;
        } catch (Exception e) {
            log.warn("SUBMITTING 대사 결과 기록 실패 — 불명 유지: {}", e.getMessage());
            return false;
        }
    }
}
