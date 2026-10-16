package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 주문 1건 증권사 접수 단일 경로 — 선점(PLANNED→SUBMITTING) → 접수 → PLACED/FAILED 기록. 배치 접수·재주문 즉시 접수 공용
// 결과 불명(I/O·타임아웃 등)은 SUBMITTING으로 남겨 어떤 재실행도 재접수하지 않는다 — 대사·런북 정정 대상
@Component
@RequiredArgsConstructor
@Slf4j
class OrderSubmitter {

    enum Outcome { PLACED, REJECTED, UNKNOWN, SKIPPED }

    // 접수 결과 — externalOrderId는 PLACED일 때만
    record Submission(Outcome outcome, String externalOrderId) {}

    private final OrderPort orderPort;
    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final ApplicationEventPublisher eventPublisher;

    Submission submit(Order order, BrokerAccountRef account, String accountLabel) {
        // 선점 — 그 사이 취소·삭제·재캡 취소됐으면 HTTP를 보내지 않는다
        try {
            orderPort.markSubmitting(order.id());
        } catch (OrderStateConflictException e) {
            log.info("[{}] 접수 직전 주문이 취소·정리됨 — 접수 생략 (orderId={})", accountLabel, order.id());
            return new Submission(Outcome.SKIPPED, null);
        }

        OrderResult result;
        try {
            result = brokerOrderCorrectionPort.place(instructionOf(order), account);
        } catch (Exception e) {
            return BrokerApiException.isRejection(e) ? rejected(order, accountLabel, e) : unknown(order, accountLabel, e);
        }

        // 증권사 접수 성공 후 DB 동기화 — 실패해도 SUBMITTING이라 재접수되지 않는다(1회 재시도로 창 축소)
        try {
            markPlacedWithRetry(order.id(), result.externalOrderId());
            return new Submission(Outcome.PLACED, result.externalOrderId());
        } catch (OrderStateConflictException e) {
            cancelRacedOrder(order, result.externalOrderId(), account, accountLabel);
            return new Submission(Outcome.SKIPPED, null);
        } catch (Exception e) {
            log.error("[{}] {} {} 증권사 접수 완료됐으나 DB PLACED 기록 실패 — 수동 확인 필요 (externalOrderId={}): {}",
                    accountLabel, order.direction(), order.ticker(), result.externalOrderId(), e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[DB 불일치] %s %s %s 증권사 접수 완료 후 PLACED 기록 실패 — orderId=%s, externalOrderId=%s. DB는 SUBMITTING이라 자동 재접수되지 않음 — 런북으로 정정"
                            .formatted(accountLabel, order.ticker(), order.direction(), order.id(), result.externalOrderId())));
            return new Submission(Outcome.UNKNOWN, null);
        }
    }

    // 증권사 접수 지시 — 주문 id를 멱등키로 실어 같은 주문의 재요청을 안전하게 한다(Toss만 사용)
    static OrderInstruction instructionOf(Order order) {
        return new OrderInstruction(order.ticker(), order.direction(), order.orderType(), order.quantity(), order.price(),
                order.id().toString());
    }

    // 확정 거절 — FAILED 기록(그 사이 다른 경로가 상태를 바꿨으면 그 상태 존중)
    private Submission rejected(Order order, String accountLabel, Exception e) {
        log.warn("[{}] {} {} 주문 접수 실패: {}", accountLabel, order.direction(), order.ticker(), e.getMessage());
        eventPublisher.publishEvent(new TradingErrorEvent(null, "[%s %s %s 접수 실패] orderId=%s — %s"
                .formatted(accountLabel, order.ticker(), order.direction(), order.id(), e.getMessage())));
        try {
            orderPort.markFailed(order.id());
        } catch (OrderStateConflictException conflict) {
            log.info("[{}] 접수 실패 주문이 그 사이 정리됨 — FAILED 기록 생략 (orderId={})", accountLabel, order.id());
        }
        return new Submission(Outcome.REJECTED, null);
    }

    // 결과 불명 — 증권사가 접수했을 수 있어 FAILED로 쓰지 않는다(FAILED는 슬롯을 비워 재실행이 같은 leg를 다시 만든다)
    private Submission unknown(Order order, String accountLabel, Exception e) {
        log.error("[{}] {} {} 주문 접수 결과 불명 — SUBMITTING 유지 (orderId={}): {}",
                accountLabel, order.direction(), order.ticker(), order.id(), e.getMessage());
        eventPublisher.publishEvent(new TradingErrorEvent(null,
                "[접수 결과 불명] %s %s %s orderId=%s — %s. 자동 재접수되지 않음 — 증권사 확인 후 런북 정정"
                        .formatted(accountLabel, order.ticker(), order.direction(), order.id(), e.getMessage())));
        return new Submission(Outcome.UNKNOWN, null);
    }

    // PLACED 기록 경합(선점 뒤 다른 경로가 상태를 바꿈) — 증권사 주문을 즉시 취소해 DB와 맞춘다
    private void cancelRacedOrder(Order order, String externalOrderId, BrokerAccountRef account, String accountLabel) {
        try {
            brokerOrderCorrectionPort.cancel(new CancelInstruction(order.ticker(), externalOrderId), account);
            log.warn("[{}] {} {} 접수 중 DB 주문 상태가 바뀜 — 증권사 주문 즉시 취소 (orderId={}, externalOrderId={})",
                    accountLabel, order.direction(), order.ticker(), order.id(), externalOrderId);
        } catch (Exception e) {
            log.error("[{}] {} {} 접수 중 상태가 바뀐 주문의 증권사 취소 실패 — 수동 취소 필요 (externalOrderId={}): {}",
                    accountLabel, order.direction(), order.ticker(), externalOrderId, e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null,
                    "[취소 경합] %s %s %s 접수 직후 DB 주문 상태가 바뀌어 증권사 취소를 시도했으나 실패 — orderId=%s, externalOrderId=%s. 증권사에서 수동 취소 필요"
                            .formatted(accountLabel, order.ticker(), order.direction(), order.id(), externalOrderId)));
        }
    }

    // 일시적 DB 오류 흡수 — 1초 후 1회 재시도, 2차 실패는 호출측으로 전파 (상태 경합은 재시도로 풀리지 않아 즉시 전파)
    private void markPlacedWithRetry(UUID orderId, String externalOrderId) {
        try {
            orderPort.markPlaced(orderId, externalOrderId);
        } catch (OrderStateConflictException conflict) {
            throw conflict;
        } catch (Exception first) {
            log.warn("markPlaced 1차 실패 — 1초 후 재시도: {}", first.getMessage());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            orderPort.markPlaced(orderId, externalOrderId);
        }
    }
}
