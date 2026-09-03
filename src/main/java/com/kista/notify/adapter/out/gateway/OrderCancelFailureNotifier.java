package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.OrderCancelFailedEvent;
import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// OrderCancelService.cancelByCycle의 취소 실패를 관리자에게 통지 (텔레그램 HTTP 호출을 트랜잭션 밖으로 격리)
// OrderCancelService가 비-트랜잭션이므로 fallbackExecution으로 트랜잭션 없어도 즉시 동기 실행 (CycleLifecycleNotifier와 동일 패턴)
@Component
@RequiredArgsConstructor
public class OrderCancelFailureNotifier {

    private final NotifyPort notifyPort; // Spring 프록시 경유 호출 — ErrorLogAspect가 app_error_logs에 기록

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCancelFailed(OrderCancelFailedEvent event) {
        notifyPort.notifyError(new IllegalStateException(
                "주문 취소 실패 " + event.failedCount() + "건 — strategyId=" + event.strategyId()
                        + ": " + event.summary()));
    }
}
