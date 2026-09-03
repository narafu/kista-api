package com.kista.notify.adapter.out.gateway;

import com.kista.market.application.event.FearGreedFetchFailedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

// market이 발행하는 FearGreedFetchFailedEvent가 기존 NotifyPort.notifyError로 정확히 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class MarketAlertNotifierTest {

    @Mock NotifyPort notifyPort;

    private MarketAlertNotifier notifier() {
        return new MarketAlertNotifier(notifyPort);
    }

    @Test
    void onFearGreedFetchFailed_callsNotifyPortWithMessage() {
        notifier().onFearGreedFetchFailed(new FearGreedFetchFailedEvent("crypto api down"));

        verify(notifyPort).notifyError(argThat(e -> "crypto api down".equals(e.getMessage())));
    }
}
