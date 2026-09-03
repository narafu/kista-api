package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.OrderCancelFailedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.event.TransactionalEventListener;

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

    // OrderCancelService는 비-트랜잭션이므로 fallbackExecution=true가 아니면 이벤트가 조용히 폐기되어 알림이 유실됨
    @Test
    void onOrderCancelFailed_hasFallbackExecutionEnabled() throws NoSuchMethodException {
        TransactionalEventListener annotation = OrderCancelFailureNotifier.class
                .getDeclaredMethod("onOrderCancelFailed", OrderCancelFailedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.fallbackExecution()).isTrue();
    }
}
