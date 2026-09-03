package com.kista.notify.adapter.out.gateway;

import com.kista.trading.application.event.CycleCompletedEvent;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.notify.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 사이클 종료/신규 시작 알림을 채널(Telegram/FCM) 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
class CycleLifecycleNotifier {

    private final UserNotificationPort userNotificationPort; // 채널 라우팅 담당 어댑터

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(fallbackExecution = true)
    public void onCycleCompleted(CycleCompletedEvent event) {
        userNotificationPort.notifyCycleCompleted(event.user(), event.account(), event.strategy());
    }

    // 트랜잭션 있으면 커밋 후, 없으면 즉시 동기 실행
    @TransactionalEventListener(fallbackExecution = true)
    public void onNewCycleStarted(NewCycleStartedEvent event) {
        userNotificationPort.notifyNewCycleStarted(event.user(), event.account(), event.strategy(), event.initialUsdDeposit());
    }
}
