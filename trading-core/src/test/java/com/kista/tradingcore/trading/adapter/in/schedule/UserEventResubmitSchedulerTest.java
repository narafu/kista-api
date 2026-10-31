package com.kista.tradingcore.trading.adapter.in.schedule;

import com.kista.tradingcore.trading.application.port.output.TradingErrorReportPort;
import com.kista.shared.kernel.AppErrorRaisedEvent;
import com.kista.shared.kernel.UserDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventResubmitSchedulerTest {

    @Mock FailedEventPublications failedEventPublications;
    @Mock TradingErrorReportPort errorReportPort;
    @InjectMocks UserEventResubmitScheduler scheduler;

    private static EventPublication publication(Object event, int attempts) {
        EventPublication publication = mock(EventPublication.class);
        when(publication.getEvent()).thenReturn(event);
        lenient().when(publication.getCompletionAttempts()).thenReturn(attempts);
        return publication;
    }

    @Test
    void 사용자_동기화_이벤트만_재발행_대상이다() {
        assertThat(scheduler.isUserSyncEventAndAlertOnFirstFailure(publication(new AppErrorRaisedEvent("t", "m", "s", Map.of()), 1))).isFalse();
        verify(errorReportPort, never()).reportError(any());
    }

    @Test
    void 첫_실패_발견_회차에만_관리자에게_알린다() {
        assertThat(scheduler.isUserSyncEventAndAlertOnFirstFailure(publication(new UserDeletedEvent(UUID.randomUUID()), 1))).isTrue();
        verify(errorReportPort).reportError(any());
    }

    @Test
    void 재시도_회차에는_알리지_않고_재발행만_한다() {
        assertThat(scheduler.isUserSyncEventAndAlertOnFirstFailure(publication(new UserDeletedEvent(UUID.randomUUID()), 2))).isTrue();
        verify(errorReportPort, never()).reportError(any());
    }
}
