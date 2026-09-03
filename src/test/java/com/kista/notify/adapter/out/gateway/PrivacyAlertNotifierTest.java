package com.kista.notify.adapter.out.gateway;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

// privacy가 발행하는 PrivacyAlertRaisedEvent가 severity별로 기존 NotifyPort 채널로 라우팅되는지 검증
@ExtendWith(MockitoExtension.class)
class PrivacyAlertNotifierTest {

    @Mock NotifyPort notifyPort;

    private PrivacyAlertNotifier notifier() {
        return new PrivacyAlertNotifier(notifyPort);
    }

    @Test
    void blockingSeverity_routesToNotifyError() {
        notifier().onPrivacyAlert(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.BLOCKING, "차단 사유"));

        verify(notifyPort).notifyError(argThat(e -> "차단 사유".equals(e.getMessage())));
    }

    @Test
    void warningSeverity_routesToNotifyInfo() {
        notifier().onPrivacyAlert(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.WARNING, "경고 메시지"));

        verify(notifyPort).notifyInfo("경고 메시지");
    }
}
