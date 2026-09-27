package com.kista.notify.adapter.out.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

// 텔레그램 Bot API HTTP 전송 공통 유틸 — sendMessage는 notify 모듈 내 다른 adapter 패키지(adapter.in.telegram)에서도 재사용하기 위해 public
@Slf4j
@RequiredArgsConstructor
public class TelegramHttpClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestClient telegramRestClient;

    // 일반 텍스트 메시지 전송
    public void sendMessage(String chatId, String text, String botToken) {
        post(botToken, "sendMessage", Map.of("chat_id", chatId, "text", text, "parse_mode", "HTML"),
                "Telegram 메시지 전송 실패");
    }

    // 인라인 버튼이 포함된 메시지 전송 (callback_data 버튼 목록)
    void sendWithInlineKeyboard(String chatId, String text, String botToken,
                                List<Map<String, String>> buttons) {
        Map<String, Object> body = Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML",
                "reply_markup", Map.of("inline_keyboard", List.of(buttons))
        );
        post(botToken, "sendMessage", body, "Telegram 인라인 버튼 메시지 전송 실패");
    }

    // 인라인 버튼 클릭 후 버튼의 로딩 스피너 제거 (TelegramApiClient에서 이관)
    public void answerCallbackQuery(String callbackQueryId, String botToken) {
        post(botToken, "answerCallbackQuery", Map.of("callback_query_id", callbackQueryId),
                "answerCallbackQuery 실패");
    }

    // sendMessage/sendWithInlineKeyboard/answerCallbackQuery 공통 전송 로직 — 빈 토큰 가드 + POST + 오류 로깅
    private void post(String botToken, String endpoint, Object body, String errorMessage) {
        if (botToken == null || botToken.isBlank()) return;
        try {
            String url = API_BASE + "/bot" + botToken + "/" + endpoint;
            telegramRestClient.post().uri(url).body(body).retrieve().body(String.class);
        } catch (Exception e) {
            log.error("{}: {}", errorMessage, e.getMessage());
        }
    }
}
