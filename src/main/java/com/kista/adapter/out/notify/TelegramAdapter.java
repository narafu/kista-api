package com.kista.adapter.out.notify;

import com.kista.domain.model.Account;
import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingReport;
import com.kista.domain.model.User;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort, UserNotificationPort {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate telegramRestTemplate; // 빈 이름: telegramRestTemplate
    private final TelegramProperties props;

    @Override
    public void notifyReport(TradingReport r) {
        String text = String.format(
                "<b>매매 결산 [%s]</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.vars().quantity(), r.vars().averagePrice(),
                r.vars().priceOffsetRate(), r.vars().targetPrice());
        send(text);
    }

    @Override
    public void notifyMarketClosed() {
        send("오늘은 휴장일입니다. 매매를 건너뜁니다.");
    }

    @Override
    public void notifyInsufficientBalance(AccountBalance b) {
        send(String.format("잔고 부족: SOXL %d주, 예수금 $%.2f. 매매를 건너뜁니다.",
                b.quantity(), b.usdDeposit()));
    }

    @Override
    public void notifyError(Exception e) {
        send(String.format("<b>⚠️ 매매 오류 발생</b>%n%s", e.getMessage()));
    }

    // ── UserNotificationPort 구현 ──────────────────────────────────────────────

    @Override
    public void notifyNewUser(User user) {
        // 관리자에게 신규 가입 알림 + [승인]/[거절] 인라인 버튼
        String text = String.format("🆕 <b>신규 가입 신청</b>%n닉네임: %s%nUID: %s",
                user.nickname(), user.id());
        sendWithInlineKeyboard(props.chatId(), text, props.botToken(),
                List.of(
                        Map.of("text", "✅ 승인", "callback_data", "approve:" + user.id()),
                        Map.of("text", "❌ 거절", "callback_data", "reject:" + user.id())
                ));
    }

    @Override
    public void notifyApproved(User user) {
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            sendMessage(user.telegramChatId(), "✅ 가입이 승인되었습니다. KISTA에 오신 걸 환영합니다!",
                    user.telegramBotToken());
        }
    }

    @Override
    public void notifyRejected(User user) {
        if (user.telegramChatId() != null && user.telegramBotToken() != null) {
            sendMessage(user.telegramChatId(), "❌ 가입 신청이 거절되었습니다.", user.telegramBotToken());
        }
    }

    @Override
    public void notifyStrategyChanged(User user, Account account, String action) {
        String text = String.format("사용자 %s이 계좌 %s의 전략을 %s했습니다",
                user.nickname(), account.nickname(), action);
        send(text); // 관리자 봇으로 전송
    }

    @Override
    public void notifyTradingReport(User user, Account account, TradingReport r) {
        // 계좌별 봇 토큰이 설정된 경우에만 발송 — 미설정 시 생략 (cfc1a8da에서 우선순위 확장 예정)
        String botToken = account.telegramBotToken();
        String chatId = account.telegramChatId();
        if (botToken == null || botToken.isBlank() || chatId == null) {
            log.warn("[{}] 계좌 텔레그램 미설정 — 매매 리포트 생략", account.nickname());
            return;
        }
        String text = String.format(
                "<b>매매 결산 [%s] — %s</b>%n"
                + "매수: $%.2f | 매도: $%.2f%n"
                + "보유: %d주 @ $%.4f%n"
                + "편차율: %.4f | 목표가: $%.2f",
                r.date(), account.nickname(),
                r.totalBoughtUsd(), r.totalSoldUsd(),
                r.vars().quantity(), r.vars().averagePrice(),
                r.vars().priceOffsetRate(), r.vars().targetPrice());
        sendMessage(chatId, text, botToken);
    }

    // ── 내부 전송 헬퍼 ────────────────────────────────────────────────────────

    private void sendWithInlineKeyboard(String chatId, String text, String botToken,
                                         List<Map<String, String>> buttons) {
        if (botToken == null || botToken.isBlank()) {
            log.warn("botToken 미설정 — 인라인 버튼 메시지 생략");
            return;
        }
        try {
            String url = API_BASE + "/bot" + botToken + "/sendMessage";
            Map<String, Object> body = Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML",
                    "reply_markup", Map.of("inline_keyboard", List.of(buttons))
            );
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 인라인 버튼 메시지 전송 실패: {}", e.getMessage());
        }
    }

    private void sendMessage(String chatId, String text, String botToken) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = API_BASE + "/bot" + botToken + "/sendMessage";
            Map<String, String> body = Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML");
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }

    private void send(String text) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram botToken 미설정 — 메시지 전송 생략: {}", text);
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            Map<String, String> body = Map.of(
                    "chat_id", props.chatId(),
                    "text", text,
                    "parse_mode", "HTML");
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
