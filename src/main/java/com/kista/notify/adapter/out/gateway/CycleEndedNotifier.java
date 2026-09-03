package com.kista.notify.adapter.out.gateway;

import com.kista.application.port.output.AccountPort;
import com.kista.application.port.output.UserPort;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;
import com.kista.trading.application.event.CycleEndedEvent;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 관리자 수동 체결 보정으로 사이클이 종료됨을 트랜잭션 커밋 후 사용자에게 알림 (SSE/FCM/텔레그램 호출을 트랜잭션 밖으로 격리)
@Component
@RequiredArgsConstructor
public class CycleEndedNotifier {

    private final UserNotificationPort userNotificationPort;
    private final UserPort userPort;       // 이벤트 payload가 ID만 담아 실행 시점 재조회
    private final AccountPort accountPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCycleEnded(CycleEndedEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        Account account = accountPort.findByIdOrThrow(event.accountId());
        userNotificationPort.notifyCycleCompleted(user, account, event.strategy());
    }
}
