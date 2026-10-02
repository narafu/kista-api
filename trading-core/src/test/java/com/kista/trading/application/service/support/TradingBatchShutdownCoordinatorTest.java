package com.kista.trading.application.service.support;

import com.kista.trading.application.event.TradingErrorEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TradingBatchShutdownCoordinatorTest {

    @Test
    void stop_criticalTimeout_publishesAdminAlert() {
        TradingBatchRunState state = mock(TradingBatchRunState.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(state.requestStop(Duration.ofSeconds(150))).thenReturn(false);
        TradingBatchShutdownCoordinator coordinator = new TradingBatchShutdownCoordinator(state, events, Duration.ofSeconds(150));
        coordinator.start();

        coordinator.stop();

        verify(events).publishEvent(argThat((Object e) -> e instanceof TradingErrorEvent t
                && t.userId() == null && t.message().contains("수동 확인 필요")));
    }

    @Test
    void stop_clean_noAlert() {
        TradingBatchRunState state = mock(TradingBatchRunState.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(state.requestStop(any())).thenReturn(true);
        TradingBatchShutdownCoordinator coordinator = new TradingBatchShutdownCoordinator(state, events, Duration.ofSeconds(150));
        coordinator.start();

        coordinator.stop();

        verifyNoInteractions(events);
    }
}
