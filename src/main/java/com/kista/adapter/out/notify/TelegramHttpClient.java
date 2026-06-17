package com.kista.adapter.out.notify;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

// 텔레그램 Bot API HTTP 전송 공통 유틸 — package-private (어댑터 레이어 내부 사용)
@Slf4j
@RequiredArgsConstructor
class TelegramHttpClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate telegramRestTemplate;

    // 일반 텍스트 메시지 전송
    void sendMessage(String chatId, String text, String botToken) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = API_BASE + "/bot" + botToken + "/sendMessage";
            Map<String, String> body = Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML");
            telegramRestTemplate.postForObject(url, body, String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }

    // 인라인 버튼이 포함된 메시지 전송 (callback_data 버튼 목록)
    void sendWithInlineKeyboard(String chatId, String text, String botToken,
                                List<Map<String, String>> buttons) {
        if (botToken == null || botToken.isBlank()) {
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
}
