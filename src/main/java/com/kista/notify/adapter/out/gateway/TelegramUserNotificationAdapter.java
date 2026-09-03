package com.kista.notify.adapter.out.gateway;

import com.kista.user.application.event.NewUserRegisteredEvent;
import com.kista.user.application.event.UserApprovedEvent;
import com.kista.user.application.event.UserRejectedEvent;
import com.kista.user.application.event.UserReappliedEvent;
import com.kista.user.application.port.output.UserPort;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.trading.domain.model.TradingReport;
import com.kista.user.domain.model.User;
import com.kista.notify.application.port.output.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import com.kista.sharedkernel.UserRole;
import com.kista.sharedkernel.UserStatus;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramUserNotificationAdapter implements UserNotificationPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정
    private final UserPort userPort;                     // 이벤트 payload가 ID만 담아 실행 시점 재조회

    // UserService가 발행한 이벤트를 커밋 성공 후에만 수신 — race condition 시 알림 중복 방지
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewUserRegistered(NewUserRegisteredEvent event) {
        User user = userPort.findByIdOrThrow(event.userId());
        if (user.role() == UserRole.ADMIN) {
            return; // 관리자 seed 부트스트랩은 알림 불필요
        }
        if (user.status() == UserStatus.ACTIVE) {
            notifyAutoApprovedUser(user); // 승인 불필요 설정이라 즉시 활성화된 신규 가입 — 정보성 알림만
        } else {
            notifyNewUser(user); // 승인 대기 — 승인/거절 버튼 포함
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserApproved(UserApprovedEvent event) {
        notifyApproved(userPort.findByIdOrThrow(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserRejected(UserRejectedEvent event) {
        notifyRejected(userPort.findByIdOrThrow(event.userId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserReapplied(UserReappliedEvent event) {
        notifyNewUser(userPort.findByIdOrThrow(event.userId()));
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
    public void notifyAutoApprovedUser(User user) {
        // 승인 불필요 설정으로 즉시 활성화된 신규 가입 — 관리자 조치 불필요라 버튼 없이 정보만 전달
        String text = String.format("🆕 <b>신규 가입 (자동 승인)</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }

    @Override
    public void notifyApproved(User user) {
        sendIfLinked(user, "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        String text = "❌ 가입 신청이 거절되었습니다.";
        if (user.rejectReason() != null && !user.rejectReason().isBlank()) {
            text += String.format("\n사유: %s", user.rejectReason());
        }
        sendIfLinked(user, text);
    }

    @Override
    public void notifyCycleCompleted(User user, Account account, StrategyRef strategy) {
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
    public void notifyNewCycleStarted(User user, Account account, StrategyRef strategy, java.math.BigDecimal initialUsdDeposit) {
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
    public void notifyInsufficientBalance(User user, Account account, StrategyType strategyType, StrategyTicker ticker) {
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

    @Override
    public void notifyFinanceRegistrationReminder(User user, String month) {
        sendIfLinked(user, String.format("📒 %s 가계부(자산·수입·소비·저축) 등록이 아직 없어요. 지금 등록해보세요.", month));
    }

    // 사용자 봇 연결 시에만 발송 — 미연결 시 조용히 skip
    private void sendIfLinked(User user, String text) {
        if (!user.hasTelegramBot()) return;
        telegramHttpClient.sendMessage(user.telegramChatId(), text, user.telegramBotToken());
    }
}
