package com.kista.notify.application.port.output;

import com.kista.notify.domain.model.NotificationRecipient;

public interface UserNotificationPort {
    void notifyNewUser(NotificationRecipient user);                                    // 관리자에게 신규 가입 승인 요청 알림 (승인 대기, 버튼 포함)
    void notifyAutoApprovedUser(NotificationRecipient user);                           // 관리자에게 자동 승인된 신규 가입 알림 (승인 불필요 설정, 버튼 없음)
    void notifyApproved(NotificationRecipient user);                                   // 사용자에게 승인 알림
    void notifyRejected(NotificationRecipient user);                                   // 사용자에게 거절 알림
}
