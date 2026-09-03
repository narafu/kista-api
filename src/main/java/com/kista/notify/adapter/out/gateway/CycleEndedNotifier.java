package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.notify.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 관리자 수동 체결 보정으로 사이클이 종료됨을 트랜잭션 커밋 후 사용자에게 알림 (SSE/FCM/텔레그램 호출을 트랜잭션 밖으로 격리)
@Component
@RequiredArgsConstructor
public class CycleEndedNotifier {

    private final UserNotificationPort userNotificationPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCycleEnded(CycleEndedEvent event) {
        userNotificationPort.notifyCycleCompleted(event.user(), event.account(), event.strategy());
    }
}
