package com.kista.platform.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

// 텔레그램 Bot API HTTP 전송 공통 유틸 — root notify·admin(봇 명령)·trading-core tradingnotify가 공용으로 사용
@Slf4j
@RequiredArgsConstructor
public class TelegramHttpClient {

    private static final String API_BASE = "https://api.telegram.org";
    private static final Pattern BOT_TOKEN_PATTERN = Pattern.compile("/bot[^/\\s\"']+"); // URL 경로의 봇 토큰 구간

    private final RestClient telegramRestClient;

    // 일반 텍스트 메시지 전송
    public void sendMessage(String chatId, String text, String botToken) {
        post(botToken, "sendMessage", Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML"),
                "Telegram 메시지 전송 실패");
    }

    // 인라인 버튼이 포함된 메시지 전송 (callback_data 버튼 목록)
    public void sendWithInlineKeyboard(String chatId, String text, String botToken,
                                       List<Map<String, String>> buttons) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML",
                "reply_markup", Map.of("inline_keyboard", List.of(buttons))
        );
        post(botToken, "sendMessage", body, "Telegram 인라인 버튼 메시지 전송 실패");
    }

    // 인라인 버튼 클릭 후 버튼의 로딩 스피너 제거
    public void answerCallbackQuery(String callbackQueryId, String botToken) {
        post(botToken, "answerCallbackQuery", Map.of("callback_query_id", callbackQueryId),
                "answerCallbackQuery 실패");
    }

    // getMe로 봇 username 조회 — 위 발송 메서드와 달리 오류를 삼키지 않고 IllegalArgumentException으로 전파(토큰 검증 용도)
    public String getBotUsername(String botToken) {
        try {
            String url = API_BASE + "/bot" + botToken + "/getMe";
            Map<String, Object> response = telegramRestClient.get().uri(url).retrieve().body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
                throw new IllegalArgumentException("유효하지 않은 Bot Token입니다");
            }
            // 응답 구조: { ok: true, result: { username: "narafu_kista_bot", ... } }
            // result가 없거나 Map이 아니거나 username이 String이 아니면 NPE/ClassCastException 대신 IAE로 통일
            if (!(response.get("result") instanceof Map<?, ?> result)
                    || !(result.get("username") instanceof String username)
                    || username.isBlank()) {
                throw new IllegalArgumentException("봇 username을 가져올 수 없습니다");
            }
            return username;
        } catch (RestClientException e) {
            // 예외 메시지에 요청 URL(/bot<token>/...)이 포함되므로 토큰을 마스킹한 뒤 로그·응답에 사용
            log.warn("Telegram getMe 실패: {}", redactToken(e.getMessage()));
            throw new IllegalArgumentException("유효하지 않은 Bot Token입니다: " + redactToken(e.getMessage()), e);
        }
    }

    // sendMessage/sendWithInlineKeyboard/answerCallbackQuery 공통 전송 로직 — 빈 토큰 가드 + POST + 오류 로깅
    private void post(String botToken, String endpoint, Object body, String errorMessage) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = API_BASE + "/bot" + botToken + "/" + endpoint;
            telegramRestClient.post().uri(url).body(body).retrieve().body(String.class);
        } catch (Exception e) {
            log.error("{}: {}", errorMessage, redactToken(e.getMessage()));
        }
    }

    // 예외 메시지 속 요청 URL의 봇 토큰(/bot<token>)을 마스킹 — null-safe
    private static String redactToken(String message) {
        return message == null ? null : BOT_TOKEN_PATTERN.matcher(message).replaceAll("/bot***");
    }
}
