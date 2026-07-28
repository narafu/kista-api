package com.kista.adapter.out.notify;

import com.kista.application.event.OrderCancelFailedEvent;
import com.kista.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// OrderCancelService.cancelByCycle의 취소 실패를 트랜잭션 커밋 후 관리자에게 통지 (텔레그램 HTTP 호출을 트랜잭션 밖으로 격리)
@Component
@RequiredArgsConstructor
public class OrderCancelFailureNotifier {

    private final NotifyPort notifyPort; // Spring 프록시 경유 호출 — ErrorLogAspect가 app_error_logs에 기록

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelFailed(OrderCancelFailedEvent event) {
        notifyPort.notifyError(new IllegalStateException(
                "주문 취소 실패 " + event.failedCount() + "건 — strategyId=" + event.strategyId()
                        + ": " + event.summary()));
    }
}
