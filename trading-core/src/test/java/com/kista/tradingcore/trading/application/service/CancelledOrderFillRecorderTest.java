package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.application.port.output.ExecutionPort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.trading.domain.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelledOrderFillRecorderTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 3);

    @Mock ExecutionPort executionPort;
    @Mock OrderCancelStateWriter stateWriter;
    @Mock BrokerAccountRef ref;
    CancelledOrderFillRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new CancelledOrderFillRecorder(executionPort, stateWriter);
    }

    private static Order cancelled(String externalId, StrategyTicker ticker) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), DAY, ticker,
                OrderType.LOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.TEN, BigDecimal.valueOf(25),
                OrderStatus.CANCELLED, externalId, null, null);
    }

    private static Execution fill(String externalId, int qty, String price) {
        return Execution.ofManualFill(DAY, StrategyTicker.SOXL, OrderDirection.BUY, qty, new BigDecimal(price), externalId);
    }

    @Test
    void 체결_있으면_합산한_수량과_가중평균가를_기록한다() {
        Order o = cancelled("A", StrategyTicker.SOXL);
        when(executionPort.getExecutions(DAY, DAY, StrategyTicker.SOXL, ref))
                .thenReturn(List.of(fill("A", 2, "10.00"), fill("A", 1, "13.00"), fill("B", 5, "99.00")));

        recorder.record(List.of(o), ref);

        verify(stateWriter).recordCancelledFill(o.id(), BigDecimal.valueOf(3), new BigDecimal("11.00"));
    }

    @Test
    void 체결_없으면_0과_null을_기록한다() {
        Order o = cancelled("A", StrategyTicker.SOXL);
        when(executionPort.getExecutions(any(), any(), any(), any())).thenReturn(List.of(fill("B", 1, "10.00")));

        recorder.record(List.of(o), ref);

        verify(stateWriter).recordCancelledFill(o.id(), BigDecimal.ZERO, null);
    }

    @Test
    void 조회_실패면_기록하지_않고_예외도_던지지_않는다() {
        when(executionPort.getExecutions(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        recorder.record(List.of(cancelled("A", StrategyTicker.SOXL)), ref);

        verifyNoInteractions(stateWriter);
    }

    @Test
    void 기록_쓰기가_실패해도_예외를_던지지_않고_나머지_주문은_계속_기록한다() {
        Order a = cancelled("A", StrategyTicker.SOXL);
        Order b = cancelled("B", StrategyTicker.SOXL);
        when(executionPort.getExecutions(any(), any(), any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("db")).when(stateWriter).recordCancelledFill(eq(a.id()), any(), any());

        recorder.record(List.of(a, b), ref);

        verify(stateWriter).recordCancelledFill(b.id(), BigDecimal.ZERO, null);
    }

    @Test
    void 같은_거래일_종목의_여러_주문은_조회_1회로_묶는다() {
        Order a = cancelled("A", StrategyTicker.SOXL);
        Order b = cancelled("B", StrategyTicker.SOXL);
        Order c = cancelled("C", StrategyTicker.TQQQ);
        when(executionPort.getExecutions(any(), any(), any(), any())).thenReturn(List.of());

        recorder.record(List.of(a, b, c), ref);

        verify(executionPort, times(1)).getExecutions(DAY, DAY, StrategyTicker.SOXL, ref);
        verify(executionPort, times(1)).getExecutions(DAY, DAY, StrategyTicker.TQQQ, ref);
        verify(stateWriter, times(3)).recordCancelledFill(any(), any(), any());
    }
}
