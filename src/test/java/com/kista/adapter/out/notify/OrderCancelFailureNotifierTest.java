package com.kista.adapter.out.notify;

import com.kista.application.event.OrderCancelFailedEvent;
import com.kista.domain.port.out.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCancelFailureNotifierTest {

    @Mock NotifyPort notifyPort;

    @Test
    void onOrderCancelFailed_forwardsSummaryToNotifyPort() {
        OrderCancelFailureNotifier notifier = new OrderCancelFailureNotifier(notifyPort);
        UUID strategyId = UUID.randomUUID();
        OrderCancelFailedEvent event = new OrderCancelFailedEvent(strategyId, 2, "orderId=a: 오류1; orderId=b: 오류2");

        notifier.onOrderCancelFailed(event);

        ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
        verify(notifyPort).notifyError(captor.capture());
        assertThat(captor.getValue().getMessage())
                .contains(strategyId.toString())
                .contains("2건")
                .contains("orderId=a: 오류1");
    }
}
