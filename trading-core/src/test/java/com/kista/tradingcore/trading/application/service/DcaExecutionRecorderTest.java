package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.DcaRoundSkippedEvent;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DcaExecutionRecorderTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    static final String KEY = "11111111-1111-1111-1111-111111111111";
    static final String OTHER = "22222222-2222-2222-2222-222222222222";

    DcaExecutionPort port = mock(DcaExecutionPort.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    DcaExecutionRecorder recorder = new DcaExecutionRecorder(port, publisher, dcaDetailPort);
    BatchContext ctx;

    @BeforeEach
    void setUp() {
        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
        when(dcaDetailPort.findActiveByStrategyId(any())).thenReturn(Optional.of(
                TradingFixtures.dcaConfig(
                        TradingFixtures.stockDcaRule(OTHER, OrderDirection.BUY, "300", OrderType.MOC),
                        TradingFixtures.stockDcaRule(KEY, OrderDirection.SELL, "300", OrderType.MOC))));
    }

    @Test
    void publishes_skip_event_only_when_inserted() {
        when(port.insertIfAbsent(any())).thenReturn(true, false);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        verify(publisher, times(1)).publishEvent(any(DcaRoundSkippedEvent.class));
    }

    @Test
    void conditionNotMet_recordsHistoryWithoutUserNotification() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.BUY, DcaSkipReason.CONDITION_NOT_MET, "RSI 30 이상", null);
        verify(port).insertIfAbsent(any());
        verify(publisher, never()).publishEvent(any(DcaRoundSkippedEvent.class));
    }

    @Test
    void orderRejected_publishesOnlyWhenOrderedRowTransitioned() {
        com.kista.tradingcore.trading.domain.model.Order order = new com.kista.tradingcore.trading.domain.model.Order(UUID.randomUUID(),
                ctx.account().id(), ctx.currentCycle().id(), DAY, StrategyTicker.UPRO, OrderType.LOC,
                com.kista.shared.kernel.OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.ONE, new BigDecimal("10.00"),
                com.kista.shared.kernel.OrderStatus.FAILED, null, null, null);
        when(port.markOrderedSkipped(any(), any(), any(), any(), any(), any())).thenReturn(true);

        recorder.recordOrderRejected(ctx.strategy(), ctx.account(), DAY, KEY, order, DcaSkipReason.INSUFFICIENT_BALANCE, "부족");

        verify(port).markOrderedSkipped(ctx.strategy().id(), KEY, DAY, order.id(), DcaSkipReason.INSUFFICIENT_BALANCE, "부족");
        verify(publisher, times(1)).publishEvent(any(DcaRoundSkippedEvent.class));
        verify(port, never()).insertIfAbsent(any());
    }

    @Test
    void orderRejected_noOrderedRow_fallsBackToInsertThenAdminAlert() {
        com.kista.tradingcore.trading.domain.model.Order order = new com.kista.tradingcore.trading.domain.model.Order(UUID.randomUUID(),
                ctx.account().id(), ctx.currentCycle().id(), DAY, StrategyTicker.UPRO, OrderType.LOC,
                com.kista.shared.kernel.OrderTiming.AT_CLOSE, OrderDirection.BUY, BigDecimal.ONE, new BigDecimal("10.00"),
                com.kista.shared.kernel.OrderStatus.FAILED, null, null, null);
        when(port.markOrderedSkipped(any(), any(), any(), any(), any(), any())).thenReturn(false);
        when(port.insertIfAbsent(any())).thenReturn(true, false);

        recorder.recordOrderRejected(ctx.strategy(), ctx.account(), DAY, KEY, order, DcaSkipReason.INSUFFICIENT_BALANCE, "부족");
        verify(publisher, times(1)).publishEvent(any(DcaRoundSkippedEvent.class)); // 삽입 폴백 성공 → 사용자 알림

        recorder.recordOrderRejected(ctx.strategy(), ctx.account(), DAY, KEY, order, DcaSkipReason.INSUFFICIENT_BALANCE, "부족");
        verify(publisher, times(1)).publishEvent(any(TradingErrorEvent.class)); // 전이·삽입 모두 불가 → 관리자 알림
    }

    @Test
    void skip_event_carries_rule_key_direction_and_number() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.NO_HOLDINGS, "가용 보유 수량 0", null);
        org.mockito.ArgumentCaptor<DcaRoundSkippedEvent> captor = org.mockito.ArgumentCaptor.forClass(DcaRoundSkippedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().ruleKey()).isEqualTo(KEY);
        assertThat(captor.getValue().direction()).isEqualTo(OrderDirection.SELL);
        assertThat(captor.getValue().ruleNumber()).isEqualTo(2); // 입력 순서 두 번째 규칙
        verify(port).insertIfAbsent(argThat(e -> KEY.equals(e.ruleKey()) && e.tradeDate().equals(DAY) && e.direction() == OrderDirection.SELL));
    }

    @Test
    void data_unavailable_also_alerts_admin_once() {
        when(port.insertIfAbsent(any())).thenReturn(true, false);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        verify(publisher, times(1)).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void exchangeApiErrorAlsoNotifiesAdmin() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", null);
        verify(publisher, times(1)).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void non_data_skip_does_not_alert_admin() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, KEY, OrderDirection.SELL, DcaSkipReason.BELOW_ONE_SHARE, "x", new BigDecimal("87.12"));
        verify(publisher, never()).publishEvent(any(TradingErrorEvent.class));
    }
}
