package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.CycleCompletedEvent;
import com.kista.trading.application.event.NewCycleStartedEvent;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

// 사이클 종료/신규 시작 알림을 채널(Telegram/FCM) 라우팅과 분리 — 발행처가 트랜잭션 안이든 밖이든 fallbackExecution으로 항상 실행되게 함
@Component
@RequiredArgsConstructor
class CycleLifecycleNotifier {

    private final UserNotificationPort userNotificationPort;
    private final UserPort userPort;
    private final AccountPort accountPort;

    @TransactionalEventListener(fallbackExecution = true)
    public void onCycleCompleted(CycleCompletedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyCycleCompleted(user, account, event.strategy());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onNewCycleStarted(NewCycleStartedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyNewCycleStarted(user, account, event.strategy(), event.initialUsdDeposit());
    }
}
