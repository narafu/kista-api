package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;

import java.math.BigDecimal;

public interface UserNotificationPort {
    void notifyNewUser(User user);                                                          // 관리자에게 신규 가입 승인 요청 알림
    void notifyApproved(User user);                                                         // 사용자에게 승인 알림
    void notifyRejected(User user);                                                         // 사용자에게 거절 알림
    void notifyTradingReport(User user, Account account, TradingReport report);             // 사용자에게 매매 결과 알림
    void notifyCycleCompleted(User user, Account account, Strategy strategy);               // 사용자에게 사이클 종료(holdings=0) 알림
    void notifyNewCycleStarted(User user, Account account, Strategy strategy,
                               BigDecimal initialUsdDeposit);                              // 사용자에게 새 사이클 시작 알림
    void notifyInsufficientBalance(User user, Account account, Strategy.Ticker ticker);    // 사용자에게 예수금 부족 알림
    void notifyError(User user, Exception e);                                              // 사용자에게 매매 오류 알림
}
