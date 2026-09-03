package com.kista.notify.adapter.out.gateway;

import com.kista.application.event.UserDeletedEvent;
import com.kista.notify.domain.port.out.NotifyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// UserCascadeDeleter의 cascade 삭제 완료를 트랜잭션 커밋 후 관리자에게 통지 (삭제 작업 완료 이력용)
@Component
@RequiredArgsConstructor
public class UserDeletedNotifier {

    private final NotifyPort notifyPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        notifyPort.notifyInfo("[userId=" + event.userId() + "] 사용자 cascade 삭제 완료");
    }
}
