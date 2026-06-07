package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.tradingcycle.TradingCycle;
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
        if (user.notificationChannel().includesTelegram()) telegram.notifyApproved(user);
        if (user.notificationChannel().includesFcm())      fcm.notifyApproved(user);
    }

    @Override
    public void notifyRejected(User user) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyRejected(user);
        if (user.notificationChannel().includesFcm())      fcm.notifyRejected(user);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport report) {
        if (user.notificationChannel().includesTelegram()) telegram.notifyTradingReport(user, account, report);
        if (user.notificationChannel().includesFcm())      fcm.notifyTradingReport(user, account, report);
    }
}
