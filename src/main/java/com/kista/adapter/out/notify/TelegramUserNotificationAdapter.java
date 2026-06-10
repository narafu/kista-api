package com.kista.adapter.out.notify;

import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.application.event.TradingCyclePausedEvent;
import com.kista.application.event.TradingCycleResumedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramUserNotificationAdapter implements UserNotificationPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정

    // StrategyService가 발행한 중지 이벤트를 커밋 성공 후에만 수신
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCyclePaused(TradingCyclePausedEvent event) {
        notifyStrategyChanged(event.user(), event.account(), event.strategy(), "중지");
    }

    // StrategyService가 발행한 재개 이벤트를 커밋 성공 후에만 수신
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCycleResumed(TradingCycleResumedEvent event) {
        notifyStrategyChanged(event.user(), event.account(), event.strategy(), "재개");
    }

    // UserService가 발행한 이벤트를 커밋 성공 후에만 수신 — race condition 시 알림 중복 방지
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
        if (event.user().status() == User.UserStatus.ACTIVE) {
            return; // 관리자 시드 등 이미 승인된 사용자는 알림 불필요
        }
        notifyNewUser(event.user());
    }

    @Override
    public void notifyNewUser(User user) {
        // 관리자에게 신규 가입 알림 + [승인]/[거절] 인라인 버튼
        String text = String.format("🆕 <b>신규 가입 신청</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        telegramHttpClient.sendWithInlineKeyboard(props.chatId(), text, props.botToken(),
                List.of(
                        Map.of("text", "✅ 승인", "callback_data", "approve:" + user.id()),
                        Map.of("text", "❌ 거절", "callback_data", "reject:" + user.id())
                ));
    }

    @Override
    public void notifyApproved(User user) {
        // 사용자 개인 봇으로 승인 알림 전송
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            telegramHttpClient.sendMessage(user.telegramChatId(), "✅ 가입이 승인되었습니다.",
                    user.telegramBotToken());
        }
    }

    @Override
    public void notifyRejected(User user) {
        // 사용자 개인 봇으로 거절 알림 전송
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            telegramHttpClient.sendMessage(user.telegramChatId(), "❌ 가입 신청이 거절되었습니다.",
                    user.telegramBotToken());
        }
    }

    @Override
    public void notifyStrategyChanged(User user, Account account, Strategy strategy, String action) {
        // 관리자 봇으로 전략 변경 내용 알림
        String text = String.format("사용자 %s이 계좌 %s의 %s(%s) 전략을 %s했습니다",
                user.nickname(), account.nickname(),
                strategy.type().name(), strategy.ticker().name(), action);
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyCycleCompleted(User user, Account account, Strategy strategy) {
        // 사용자 텔레그램 봇 미설정 시 생략
        if (user.telegramBotToken() == null || user.telegramBotToken().isBlank()
                || user.telegramChatId() == null) {
            log.warn("[{}] 텔레그램 미설정 — 사이클 종료 알림 생략", account.nickname());
            return;
        }
        String text = String.format(
                "🔄 <b>사이클 종료</b> — %s%n"
                + "%s %s 전략의 매매 사이클이 완료되었습니다.%n"
                + "다음 사이클 정책: %s",
                account.nickname(),
                strategy.type().name(), strategy.ticker().name(),
                strategy.cycleSeedType().name());
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }

    @Override
    public void notifyNewCycleStarted(User user, Account account, Strategy strategy, java.math.BigDecimal initialUsdDeposit) {
        // 사용자 텔레그램 봇 미설정 시 생략
        if (user.telegramBotToken() == null || user.telegramBotToken().isBlank()
                || user.telegramChatId() == null) {
            log.warn("[{}] 텔레그램 미설정 — 사이클 시작 알림 생략", account.nickname());
            return;
        }
        String text = String.format(
                "🚀 <b>새 사이클 시작</b> — %s%n"
                + "%s %s 전략의 새 매매 사이클이 시작되었습니다.%n"
                + "시드: $%.2f",
                account.nickname(),
                strategy.type().name(), strategy.ticker().name(),
                initialUsdDeposit);
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport r) {
        // 사용자 텔레그램 봇 미설정 시 생략
        if (user.telegramBotToken() == null || user.telegramBotToken().isBlank()
                || user.telegramChatId() == null) {
            log.warn("[{}] 텔레그램 미설정 — 매매 리포트 생략", account.nickname());
            return;
        }
        String text = String.format(
                "<b>매매 결산 [%s] — %s</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(), account.nickname(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.snapshot().holdings(), r.snapshot().averagePrice(),
                r.snapshot().priceOffsetRate(), r.snapshot().targetPrice());
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }
}
