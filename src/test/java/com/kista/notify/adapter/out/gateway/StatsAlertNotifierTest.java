package com.kista.notify.adapter.out.gateway;

import com.kista.stats.application.event.StatsAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

// stats가 발행하는 StatsAlertRaisedEvent가 기존 NotifyPort.notifyError로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class StatsAlertNotifierTest {

    @Mock NotifyPort notifyPort;

    private StatsAlertNotifier notifier() {
        return new StatsAlertNotifier(notifyPort);
    }

    @Test
    void onStatsAlertRaised_callsNotifyPortWithMessage() {
        notifier().onStatsAlertRaised(new StatsAlertRaisedEvent("kbland api down"));

        verify(notifyPort).notifyError(argThat(e -> "kbland api down".equals(e.getMessage())));
    }
}
