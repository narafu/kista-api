package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.user.User.NotificationChannel;
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

    // 관리자 알림 — 채널 무관, 항상 Telegram
    @Override
    public void notifyStrategyChanged(User user, Account account, TradingCycle cycle, String action) {
        telegram.notifyStrategyChanged(user, account, cycle, action);
    }

    // 사용자 알림 — notificationChannel에 따라 라우팅
    @Override
    public void notifyApproved(User user) {
        if (usesTelegram(user)) telegram.notifyApproved(user);
        if (usesFcm(user))      fcm.notifyApproved(user);
    }

    @Override
    public void notifyRejected(User user) {
        if (usesTelegram(user)) telegram.notifyRejected(user);
        if (usesFcm(user))      fcm.notifyRejected(user);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport report) {
        if (usesTelegram(user)) telegram.notifyTradingReport(user, account, report);
        if (usesFcm(user))      fcm.notifyTradingReport(user, account, report);
    }

    // TELEGRAM 또는 ALL 채널이면 Telegram 전송
    private boolean usesTelegram(User u) {
        return u.notificationChannel() == NotificationChannel.TELEGRAM
                || u.notificationChannel() == NotificationChannel.ALL;
    }

    // FCM 또는 ALL 채널이면 FCM 전송
    private boolean usesFcm(User u) {
        return u.notificationChannel() == NotificationChannel.FCM
                || u.notificationChannel() == NotificationChannel.ALL;
    }
}
