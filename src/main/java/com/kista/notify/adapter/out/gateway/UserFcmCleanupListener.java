package com.kista.notify.adapter.out.gateway;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 사용자 탈퇴 cascade — FCM 디바이스 토큰 삭제 (기존 UserCascadeDeleter에 누락돼 있던 결함 수정,
// 발견 시점 함께 수정: 탈퇴 후에도 FCM 토큰이 남아 존재하지 않는 사용자에게 알림을 계속 시도하던 상태였음)
@Component
@RequiredArgsConstructor
class UserFcmCleanupListener {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserDeleted(UserDeletedEvent event) {
        fcmDeviceTokenPort.deleteAllByUserId(event.userId());
    }
}
