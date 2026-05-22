package com.kista.adapter.out.notify;

import com.kista.application.service.NewUserRegisteredEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.tradingcycle.TradingCycle;
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

    // UserService가 발행한 이벤트를 커밋 성공 후에만 수신 — race condition 시 알림 중복 방지
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
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
    public void notifyStrategyChanged(User user, Account account, TradingCycle cycle, String action) {
        // 관리자 봇으로 전략 변경 내용 알림
        String text = String.format("사용자 %s이 계좌 %s의 %s(%s) 전략을 %s했습니다",
                user.nickname(), account.nickname(),
                cycle.type().name(), cycle.ticker().name(), action);
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
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
