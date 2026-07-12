package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

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
    @Override public void notifyApproved(User user)                                                     { route(user, p -> p.notifyApproved(user)); }
    @Override public void notifyRejected(User user)                                                     { route(user, p -> p.notifyRejected(user)); }
    @Override public void notifyCycleCompleted(User user, Account account, Strategy strategy)           { route(user, p -> p.notifyCycleCompleted(user, account, strategy)); }
    @Override public void notifyNewCycleStarted(User user, Account account, Strategy strategy, java.math.BigDecimal d) { route(user, p -> p.notifyNewCycleStarted(user, account, strategy, d)); }
    @Override public void notifyTradingReport(User user, Account account, TradingReport report)         { route(user, p -> p.notifyTradingReport(user, account, report)); }
    @Override public void notifyInsufficientBalance(User user, Account account, Strategy.Type t, Strategy.Ticker k) { route(user, p -> p.notifyInsufficientBalance(user, account, t, k)); }
    @Override public void notifyError(User user, Exception e)                                           { route(user, p -> p.notifyError(user, e)); }
    @Override public void notifyBatchInterrupted(User user, Account account)                        { route(user, p -> p.notifyBatchInterrupted(user, account)); }
    @Override public void notifyMarketOpen(User user)                                                   { route(user, p -> p.notifyMarketOpen(user)); }
    @Override public void notifyMarketClose(User user)                                                  { route(user, p -> p.notifyMarketClose(user)); }

    // notificationChannel 기반 어댑터 라우팅 — Telegram/FCM 순서 고정
    private void route(User user, Consumer<UserNotificationPort> action) {
        if (user.notificationChannel().includesTelegram()) action.accept(telegram);
        if (user.notificationChannel().includesFcm())      action.accept(fcm);
    }
}
