package com.kista.adapter.out.notify;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.FcmDeviceTokenPort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FcmAdapter implements UserNotificationPort {

    private final FcmDeviceTokenPort fcmDeviceTokenPort;
    private final Optional<FirebaseMessaging> firebaseMessaging; // null-safe — 미설정 시 empty

    @Override
    public void notifyNewUser(User user) {
        // 신규 가입 알림은 관리자 전용 — CompositeAdapter에서 항상 Telegram 경유
    }

    @Override
    public void notifyApproved(User user) {
        send(user.id(), "KISTA 알림", "✅ 가입이 승인되었습니다.");
    }

    @Override
    public void notifyRejected(User user) {
        send(user.id(), "KISTA 알림", "❌ 가입이 거절되었습니다.");
    }

    @Override
    public void notifyCycleCompleted(User user, Account account, Strategy strategy) {
        String body = String.format("[%s] %s %s 사이클 완료",
                account.nickname(), strategy.type().name(), strategy.ticker().name());
        send(user.id(), "사이클 종료", body);
    }

    @Override
    public void notifyNewCycleStarted(User user, Account account, Strategy strategy, java.math.BigDecimal initialUsdDeposit) {
        String body = String.format("[%s] %s %s — 시드 $%.2f",
                account.nickname(), strategy.type().name(), strategy.ticker().name(), initialUsdDeposit);
        send(user.id(), "새 사이클 시작", body);
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport report) {
        String body = String.format("[%s %s] 매수 $%.2f / 매도 $%.2f",
                report.strategyType().name(), report.ticker().name(),
                report.totalBoughtUsd(), report.totalSoldUsd());
        send(user.id(), "매매 결산 — " + account.nickname(), body);
    }

    @Override
    public void notifyInsufficientBalance(User user, Account account, Strategy.Type strategyType, Strategy.Ticker ticker) {
        String body = String.format("[%s %s] 장 마감 전 예수금 확인 바랍니다.", strategyType.name(), ticker.name());
        send(user.id(), "⚠️ 예수금 부족 — " + account.nickname(), body);
    }

    @Override
    public void notifyError(User user, Exception e) {
        send(user.id(), "⚠️ 매매 오류 발생", e.getMessage());
    }

    private void send(UUID userId, String title, String body) {
        if (firebaseMessaging.isEmpty()) {
            return;
        }
        List<String> tokens = fcmDeviceTokenPort.findTokensByUserId(userId);
        if (tokens.isEmpty()) {
            return;
        }
        MulticastMessage message = MulticastMessage.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .addAllTokens(tokens)
                .build();
        try {
            var result = firebaseMessaging.get().sendEachForMulticast(message);
            // 등록 만료된 토큰 자동 삭제
            for (int i = 0; i < result.getResponses().size(); i++) {
                if (!result.getResponses().get(i).isSuccessful()) {
                    String failedToken = tokens.get(i);
                    log.warn("FCM 토큰 전송 실패, 삭제: {}", failedToken);
                    fcmDeviceTokenPort.delete(userId, failedToken);
                }
            }
        } catch (FirebaseMessagingException e) {
            log.error("FCM 전송 오류: {}", e.getMessage());
        }
    }
}
