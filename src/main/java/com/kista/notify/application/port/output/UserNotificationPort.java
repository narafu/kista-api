package com.kista.notify.application.port.output;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.trading.domain.model.TradingReport;
import com.kista.user.domain.model.User;

import java.math.BigDecimal;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

public interface UserNotificationPort {
    void notifyNewUser(User user);                                                          // 관리자에게 신규 가입 승인 요청 알림 (승인 대기, 버튼 포함)
    void notifyAutoApprovedUser(User user);                                                 // 관리자에게 자동 승인된 신규 가입 알림 (승인 불필요 설정, 버튼 없음)
    void notifyApproved(User user);                                                         // 사용자에게 승인 알림
    void notifyRejected(User user);                                                         // 사용자에게 거절 알림
    void notifyTradingReport(User user, Account account, TradingReport report);             // 사용자에게 매매 결과 알림
    void notifyCycleCompleted(User user, Account account, StrategyRef strategy);               // 사용자에게 사이클 종료(holdings=0) 알림
    void notifyNewCycleStarted(User user, Account account, StrategyRef strategy,
                               BigDecimal initialUsdDeposit);                              // 사용자에게 새 사이클 시작 알림
    void notifyInsufficientBalance(User user, Account account, StrategyType strategyType, StrategyTicker ticker); // 사용자에게 예수금 부족 알림
    void notifyError(User user, Exception e);                                              // 사용자에게 매매 오류 알림
    void notifyBatchInterrupted(User user, Account account);                                  // 사용자에게 스케쥴러 인터럽트(배포·재기동) 알림
    void notifyMarketOpen(User user);                                                        // 사용자에게 장 개시 알림
    void notifyMarketClose(User user);                                                       // 사용자에게 장 마감 알림
    void notifyFinanceRegistrationReminder(User user, String month);                          // 사용자에게 이번 달(month) 가계부 미등록 알림
}
