package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DcaExecutionRecorderTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

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
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null)));
    }

    @Test
    void publishes_skip_event_only_when_inserted() {
        when(port.insertIfAbsent(any())).thenReturn(true, false);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.NO_HOLDINGS, "판매가능수량 0주", null);
        verify(publisher, times(1)).publishEvent(any(DcaRoundSkippedEvent.class));
    }

    @Test
    void data_unavailable_also_alerts_admin_once() {
        when(port.insertIfAbsent(any())).thenReturn(true, false);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.DATA_UNAVAILABLE, "전일 종가 조회 실패", null);
        verify(publisher, times(1)).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void exchangeApiErrorAlsoNotifiesAdmin() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.EXCHANGE_API_ERROR, "주문 접수 확인 실패", null);
        verify(publisher, times(1)).publishEvent(any(TradingErrorEvent.class));
    }

    @Test
    void non_data_skip_does_not_alert_admin() {
        when(port.insertIfAbsent(any())).thenReturn(true);
        recorder.recordSkipped(ctx, DAY, DcaSkipReason.BELOW_ONE_SHARE, "x", new BigDecimal("87.12"));
        verify(publisher, never()).publishEvent(any(TradingErrorEvent.class));
    }
}
