package com.kista.notify.adapter.out.gateway;

import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.stats.application.event.StatsAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

// market/stats가 발행하는 외부 데이터 수집 실패 이벤트가 기존 NotifyPort.notifyError로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class AlertNotifierTest {

    @Mock NotifyPort notifyPort;

    private AlertNotifier notifier() {
        return new AlertNotifier(notifyPort);
    }

    @Test
    void onFearGreedFetchFailed_callsNotifyPortWithMessage() {
        notifier().onFearGreedFetchFailed(new FearGreedFetchFailedEvent("crypto api down"));

        verify(notifyPort).notifyError(argThat(e -> "crypto api down".equals(e.getMessage())));
    }

    @Test
    void onStatsAlertRaised_callsNotifyPortWithMessage() {
        notifier().onStatsAlertRaised(new StatsAlertRaisedEvent("kbland api down"));

        verify(notifyPort).notifyError(argThat(e -> "kbland api down".equals(e.getMessage())));
    }
}
