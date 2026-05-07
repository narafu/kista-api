package com.kista.domain.port.out;

import com.kista.domain.model.Account;
import com.kista.domain.model.TradingReport;
import com.kista.domain.model.User;

public interface UserNotificationPort {
    void notifyNewUser(User user);                                    // 관리자에게 신규 가입 승인 요청 알림
    void notifyApproved(User user);                                   // 사용자에게 승인 알림
    void notifyRejected(User user);                                   // 사용자에게 거절 알림
    void notifyStrategyChanged(User user, Account account, String action); // 관리자에게 전략 변경 알림
    void notifyTradingReport(User user, Account account, TradingReport report); // 사용자에게 매매 결과 알림
}
