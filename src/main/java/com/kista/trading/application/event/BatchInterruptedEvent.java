package com.kista.trading.application.event;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.user.User;

// 스케쥴러 인터럽트(배포·재기동) 사용자 알림 (UserNotificationPort.notifyBatchInterrupted)
public record BatchInterruptedEvent(User user, Account account) {}
