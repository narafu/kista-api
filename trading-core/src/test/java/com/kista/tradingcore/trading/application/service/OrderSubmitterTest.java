package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.tradingcore.broker.domain.model.OrderInstruction;
import com.kista.tradingcore.broker.domain.model.OrderResult;
import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.service.OrderSubmitter.Outcome;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// 선점 → 접수 → 기록 단일 경로와 확정 거절/결과 불명 분류 검증 (spec 2026-10-16 §3.2·§3.3)
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderSubmitter 단위 테스트")
class OrderSubmitterTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderCorrectionPort brokerPort;
    @Mock ApplicationEventPublisher eventPublisher;

    static final BrokerAccountRef ACCOUNT_REF = new BrokerAccountRef(
            UUID.randomUUID(), "key", "secret", "74420614", null, Broker.KIS);
    static final UUID ORDER_ID = UUID.randomUUID();
    static final Order ORDER = new Order(ORDER_ID, ACCOUNT_REF.id(), UUID.randomUUID(), LocalDate.now(),
            StrategyTicker.SOXL, OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.valueOf(3),
            new BigDecimal("20.00"), OrderStatus.PLANNED, null, null, null);

    private OrderSubmitter submitter() {
        return new OrderSubmitter(orderPort, brokerPort, eventPublisher);
    }

    private void expectAlert(String fragment) {
        verify(eventPublisher).publishEvent(argThat((Object ev) -> ev instanceof TradingErrorEvent tee
                && tee.message() != null && tee.message().contains(fragment)));
    }

    @Test
    @DisplayName("HTTP 전에 선점하고 접수 성공 시 PLACED 기록")
    void submit_claimsBeforeSending() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        InOrder inOrder = inOrder(orderPort, brokerPort);
        inOrder.verify(orderPort).markSubmitting(ORDER_ID);
        inOrder.verify(brokerPort).place(any(), eq(ACCOUNT_REF));
        inOrder.verify(orderPort).markPlaced(ORDER_ID, "EXT");
        assertThat(result.outcome()).isEqualTo(Outcome.PLACED);
        assertThat(result.externalOrderId()).isEqualTo("EXT");
    }

    @Test
    @DisplayName("선점 경합(그 사이 취소·삭제)이면 HTTP를 보내지 않는다")
    void submit_claimConflict_skipsWithoutHttp() {
        doThrow(new OrderStateConflictException("삭제됨")).when(orderPort).markSubmitting(ORDER_ID);

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED);
        verify(brokerPort, never()).place(any(), any());
    }

    @Test
    @DisplayName("주문 id를 증권사 멱등키(clientOrderId)로 전달")
    void submit_passesOrderIdAsClientOrderId() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));

        submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        ArgumentCaptor<OrderInstruction> captor = ArgumentCaptor.forClass(OrderInstruction.class);
        verify(brokerPort).place(captor.capture(), eq(ACCOUNT_REF));
        assertThat(captor.getValue().clientOrderId()).isEqualTo(ORDER_ID.toString());
    }

    @Test
    @DisplayName("확정 거절이면 FAILED 기록")
    void submit_rejected_marksFailed() {
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.rejected("주문가능금액 초과"));

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        verify(orderPort).markFailed(ORDER_ID);
        expectAlert("접수 실패");
    }

    @Test
    @DisplayName("BUY 잔고 부족 확정 거절은 관리자 이벤트 없이 FAILED로 기록하고 INSUFFICIENT_FUNDS를 돌려준다")
    void buyInsufficientFundsMarksFailedWithoutAdminEvent() {
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.insufficientFunds("주문가능금액 초과"));

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.INSUFFICIENT_FUNDS);
        verify(orderPort).markFailed(ORDER_ID);
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("SELL 수량 부족 확정 거절도 BUY처럼 관리자 이벤트 없이 INSUFFICIENT_FUNDS로 돌려준다")
    void sellInsufficientQuantityMarksFailedWithoutAdminEvent() {
        Order sell = new Order(ORDER_ID, ACCOUNT_REF.id(), ORDER.strategyCycleId(), LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.SELL, BigDecimal.valueOf(3), new BigDecimal("20.00"), OrderStatus.PLANNED, null, null, null);
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.insufficientFunds("매도가능수량 부족"));

        OrderSubmitter.Submission result = submitter().submit(sell, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.INSUFFICIENT_FUNDS);
        verify(orderPort).markFailed(ORDER_ID);
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("자격증명 실패(키 만료)는 확정 거절 — 매 배치 SUBMITTING을 쌓지 않는다")
    void submit_credentialFailure_marksFailed() {
        when(brokerPort.place(any(), any())).thenThrow(new BrokerCredentialException());

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.REJECTED);
        verify(orderPort).markFailed(ORDER_ID);
    }

    @Test
    @DisplayName("결과 불명(I/O 등)이면 SUBMITTING 유지 + 알림")
    void submit_ambiguous_keepsSubmitting() {
        when(brokerPort.place(any(), any())).thenThrow(new StubBrokerApiException("read timed out"));

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        verify(orderPort, never()).markFailed(any());
        expectAlert("[접수 결과 불명]");
    }

    @Test
    @DisplayName("확정 거절 FAILED 기록이 경합이어도 예외 없이 REJECTED")
    void submit_rejected_markFailedConflict_isIgnored() {
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.rejected("거절"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markFailed(ORDER_ID);

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.REJECTED);
    }

    @Test
    @DisplayName("PLACED 기록 1차 실패는 1회 재시도로 흡수")
    void submit_markPlacedFailsOnce_retries() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));
        doThrow(new RuntimeException("일시적 DB 오류")).doNothing().when(orderPort).markPlaced(ORDER_ID, "EXT");

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.PLACED);
        verify(orderPort, times(2)).markPlaced(ORDER_ID, "EXT");
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("PLACED 기록이 재시도까지 실패하면 SUBMITTING 유지 + 자동 재접수 안 됨 알림")
    void submit_markPlacedFails_keepsSubmitting() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markPlaced(ORDER_ID, "EXT");

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.UNKNOWN);
        expectAlert("자동 재접수되지 않음");
    }

    @Test
    @DisplayName("PLACED 기록 경합이면 증권사 주문 즉시 취소, 실패 시 취소 경합 알림")
    void submit_markPlacedConflict_cancelsAtBroker() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markPlaced(ORDER_ID, "EXT");
        doThrow(new RuntimeException("증권사 오류")).when(brokerPort).cancel(any(), any());

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.SKIPPED);
        verify(brokerPort).cancel(new CancelInstruction(ORDER.ticker(), "EXT"), ACCOUNT_REF);
        verify(orderPort, times(1)).markPlaced(ORDER_ID, "EXT");
        expectAlert("취소 경합");
    }

    @Test
    @DisplayName("PLACED 기록 경합 후 증권사가 이미 체결이면 DB 정정 알림")
    void submit_markPlacedConflict_alreadyFilled_alertsDbFix() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markPlaced(ORDER_ID, "EXT");
        when(brokerPort.cancel(any(), any())).thenReturn(CancelOutcome.ALREADY_FILLED);

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.SKIPPED);
        expectAlert("DB 정정 필요");
    }

    @Test
    @DisplayName("PLACED 기록 경합 후 증권사가 이미 취소면 알림 없음")
    void submit_markPlacedConflict_alreadyCanceled_noAlert() {
        when(brokerPort.place(any(), any())).thenReturn(new OrderResult("EXT"));
        doThrow(new OrderStateConflictException("경합")).when(orderPort).markPlaced(ORDER_ID, "EXT");
        when(brokerPort.cancel(any(), any())).thenReturn(CancelOutcome.ALREADY_CANCELED);

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.SKIPPED);
        verify(eventPublisher, never()).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    @DisplayName("선점 직후 인터럽트(종료 요청)면 HTTP 없이 FAILED — 송신 전이라 확정")
    void submit_interruptedAfterClaim_marksFailedWithoutHttp() {
        Thread.currentThread().interrupt();
        try {
            OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

            assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
            verify(brokerPort, never()).place(any(), any());
            verify(orderPort).markFailed(ORDER_ID);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); // 호출자 루프가 남은 주문을 중단하도록 플래그 유지
        } finally {
            Thread.interrupted(); // 다른 테스트에 플래그가 새지 않게 정리
        }
    }

    @Test
    @DisplayName("선점이 경합 아닌 DB 오류로 실패하면 그 주문만 건너뛰고(HTTP 없음) 알림 — 루프를 끊지 않는다")
    void submit_claimDbError_skipsWithAlert() {
        doThrow(new RuntimeException("lock timeout")).when(orderPort).markSubmitting(ORDER_ID);

        OrderSubmitter.Submission result = submitter().submit(ORDER, ACCOUNT_REF, "계좌");

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED);
        verify(brokerPort, never()).place(any(), any());
        expectAlert("선점 실패");
    }

    @Test
    @DisplayName("확정 거절 FAILED 기록이 DB 오류면 SUBMITTING 잔류 — 결과 불명으로 알림")
    void submit_rejected_markFailedDbError_reportsUnknown() {
        when(brokerPort.place(any(), any())).thenThrow(StubBrokerApiException.rejected("거절"));
        doThrow(new RuntimeException("DB down")).when(orderPort).markFailed(ORDER_ID);

        assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.UNKNOWN);
        expectAlert("[접수 결과 불명]");
    }

    @Test
    @DisplayName("선점 직후 인터럽트 — FAILED 기록은 인터럽트 플래그를 내린 상태로 수행(가상 스레드 소켓·커넥션 획득이 끊기지 않게)")
    void submit_interruptedAfterClaim_writesFailedWithFlagCleared() {
        doAnswer(inv -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(orderPort).markFailed(ORDER_ID);
        Thread.currentThread().interrupt();
        try {
            assertThat(submitter().submit(ORDER, ACCOUNT_REF, "계좌").outcome()).isEqualTo(Outcome.REJECTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
