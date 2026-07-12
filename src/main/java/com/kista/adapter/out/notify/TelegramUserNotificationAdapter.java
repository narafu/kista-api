package com.kista.adapter.out.notify;

import com.kista.application.event.NewUserRegisteredEvent;
import com.kista.application.event.UserApprovedEvent;
import com.kista.application.event.UserRejectedEvent;
import com.kista.application.event.UserReappliedEvent;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
        if (event.user().status() == User.UserStatus.ACTIVE) {
            return; // 관리자 시드 등 이미 승인된 사용자는 알림 불필요
        }
        notifyNewUser(event.user());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyApproved(event.user());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyRejected(event.user());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserReapplied(UserReappliedEvent event) {
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
        sendIfLinked(user, "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        String text = "❌ 가입 신청이 거절되었습니다.";
        if (user.rejectReason() != null && !user.rejectReason().isBlank()) {
            text += String.format("%n사유: %s", user.rejectReason());
        }
        sendIfLinked(user, text);
    }

    @Override
    public void notifyCycleCompleted(User user, Account account, Strategy strategy) {
        String text = String.format(
                "🔄 <b>사이클 종료</b> — %s%n"
                + "[%s] %s 사이클이 완료되었습니다.%n"
                + "다음 사이클 정책: %s",
                account.nickname(),
                strategy.type().name(), strategy.ticker().name(),
                strategy.cycleSeedType().name());
        sendIfLinked(user, text);
    }

    @Override
    public void notifyNewCycleStarted(User user, Account account, Strategy strategy, java.math.BigDecimal initialUsdDeposit) {
        String text = String.format(
                "🚀 <b>새 사이클 시작</b> — %s%n"
                + "[%s] %s 사이클이 시작되었습니다.%n"
                + "시드: $%.2f",
                account.nickname(),
                strategy.type().name(), strategy.ticker().name(),
                initialUsdDeposit);
        sendIfLinked(user, text);
    }

    @Override
    public void notifyInsufficientBalance(User user, Account account, Strategy.Type strategyType, Strategy.Ticker ticker) {
        String text = String.format(
                "⚠️ <b>예수금 부족</b> — %s%n"
                + "[%s] %s 장 마감 전 예수금 확인 바랍니다.",
                account.nickname(), strategyType.name(), ticker.name());
        sendIfLinked(user, text);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport r) {
        String text = String.format(
                "<b>매매 결산[%s]</b> — %s%n"
                + "[%s] %s 매수: $%.2f | 매도: $%.2f",
                r.date(), account.nickname(),
                r.strategyType().name(), r.ticker().name(),
                r.totalBoughtUsd(), r.totalSoldUsd());
        sendIfLinked(user, text);
    }

    @Override
    public void notifyError(User user, Exception e) {
        sendIfLinked(user, String.format("⚠️ <b>매매 오류 발생</b>%n%s", e.getMessage()));
    }

    @Override
    public void notifyBatchInterrupted(User user, Account account) {
        String text = String.format(
                "⏸️ <b>매매 일시 중단</b> — %s%n"
                + "시스템 재배포로 오늘 매매가 일시 중단됐습니다. 잠시 후 자동 재시도되거나, 필요 시 관리자에게 문의해주세요.",
                account.nickname());
        sendIfLinked(user, text);
    }

    @Override
    public void notifyMarketOpen(User user) {
        sendIfLinked(user, "🟢 미국 장이 열렸습니다.");
    }

    @Override
    public void notifyMarketClose(User user) {
        sendIfLinked(user, "🔴 미국 장이 마감되었습니다.");
    }

    // 사용자 봇 연결 시에만 발송 — 미연결 시 조용히 skip
    private void sendIfLinked(User user, String text) {
        if (!user.hasTelegramBot()) return;
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }
}
