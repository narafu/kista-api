package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
@RequiredArgsConstructor
public class CompositeUserNotificationAdapter implements UserNotificationPort {

    private final TelegramUserNotificationAdapter telegram; // 인라인 버튼 지원 — 관리자 알림 전용
    private final FcmAdapter fcm;                           // FCM 푸시 — 사용자 채널 라우팅

    // 관리자 알림 — 채널 무관, 항상 Telegram (인라인 버튼 필요)
    @Override
    public void notifyNewUser(User user) {
        telegram.notifyNewUser(user);
    }

    // 사용자 알림 — notificationChannel에 따라 라우팅
    @Override
    public void notifyApproved(User user) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyApproved(user);
        if (user.notificationChannel().includesFcm())      fcm.notifyApproved(user);
    }

    @Override
    public void notifyRejected(User user) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyRejected(user);
        if (user.notificationChannel().includesFcm())      fcm.notifyRejected(user);
    }

    @Override
    public void notifyCycleCompleted(User user, Account account, Strategy strategy) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyCycleCompleted(user, account, strategy);
        if (user.notificationChannel().includesFcm())      fcm.notifyCycleCompleted(user, account, strategy);
    }

    @Override
    public void notifyNewCycleStarted(User user, Account account, Strategy strategy, java.math.BigDecimal initialUsdDeposit) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyNewCycleStarted(user, account, strategy, initialUsdDeposit);
        if (user.notificationChannel().includesFcm())      fcm.notifyNewCycleStarted(user, account, strategy, initialUsdDeposit);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport report) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyTradingReport(user, account, report);
        if (user.notificationChannel().includesFcm())      fcm.notifyTradingReport(user, account, report);
    }

    @Override
    public void notifyInsufficientBalance(User user, Account account, Strategy.Ticker ticker) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyInsufficientBalance(user, account, ticker);
        if (user.notificationChannel().includesFcm())      fcm.notifyInsufficientBalance(user, account, ticker);
    }

    @Override
    public void notifyError(User user, Exception e) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyError(user, e);
        if (user.notificationChannel().includesFcm())      fcm.notifyError(user, e);
    }
}
