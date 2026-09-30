package com.kista.notify.adapter.out.gateway;

import com.kista.user.application.event.NewUserRegisteredEvent;
import com.kista.user.application.event.UserApprovedEvent;
import com.kista.user.application.event.UserRejectedEvent;
import com.kista.user.application.event.UserReappliedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import com.kista.notify.domain.model.NotificationRecipient;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Primary
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram; // 인라인 버튼 지원 — 관리자 알림 전용
    private final FcmAdapter fcm;                           // FCM 푸시 — 사용자 채널 라우팅
    private final UserPort userPort;                        // 이벤트 payload가 ID만 담아 실행 시점 재조회

    // UserService가 발행한 이벤트를 커밋 성공 후에만 수신 — race condition 시 알림 중복 방지
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        if (user.role() == UserRole.ADMIN) {
            return; // 관리자 seed 부트스트랩은 알림 불필요
        }
        if (user.status() == UserStatus.ACTIVE) {
            notifyAutoApprovedUser(NotificationRecipients.from(user)); // 승인 불필요 설정이라 즉시 활성화된 신규 가입 — 정보성 알림만
        } else {
            notifyNewUser(NotificationRecipients.from(user)); // 승인 대기 — 승인/거절 버튼 포함
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyApproved(NotificationRecipients.from(userPort.findByIdOrThrow(event.userId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyRejected(NotificationRecipients.from(userPort.findByIdOrThrow(event.userId())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserReapplied(UserReappliedEvent event) {
        notifyNewUser(NotificationRecipients.from(userPort.findByIdOrThrow(event.userId())));
    }

    // 관리자 알림 — 채널 무관, 항상 Telegram (인라인 버튼 필요)
    @Override
    public void notifyNewUser(NotificationRecipient user) {
        telegram.notifyNewUser(user);
    }

    @Override
    public void notifyAutoApprovedUser(NotificationRecipient user) {
        telegram.notifyAutoApprovedUser(user);
    }

    // 승인/거절 알림 — notificationChannel 설정과 무관하게 연결된 수단 전부로 발송 (각 어댑터가 자체 게이트 보유)
    @Override public void notifyApproved(NotificationRecipient user) { telegram.notifyApproved(user); fcm.notifyApproved(user); }
    @Override public void notifyRejected(NotificationRecipient user) { telegram.notifyRejected(user); fcm.notifyRejected(user); }
}
