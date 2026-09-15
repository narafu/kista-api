package com.kista.notify.application.port.output;

import com.kista.user.domain.model.User;

public interface UserNotificationPort {
    void notifyNewUser(User user);                                    // 관리자에게 신규 가입 승인 요청 알림 (승인 대기, 버튼 포함)
    void notifyAutoApprovedUser(User user);                           // 관리자에게 자동 승인된 신규 가입 알림 (승인 불필요 설정, 버튼 없음)
    void notifyApproved(User user);                                   // 사용자에게 승인 알림
    void notifyRejected(User user);                                   // 사용자에게 거절 알림
    void notifyFinanceRegistrationReminder(User user, String month);  // 사용자에게 이번 달(month) 가계부 미등록 알림
}
