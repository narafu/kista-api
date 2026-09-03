package com.kista.notify.adapter.in.telegram;

import com.kista.notify.adapter.out.gateway.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramApiClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestClient telegramRestClient; // 빈 이름: telegramRestClient
    private final TelegramProperties props;

    void sendMessage(String chatId, String text) {
        if (!props.hasBot()) {
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            telegramRestClient.post().uri(url).body(Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML")).retrieve().body(String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }

    // 인라인 버튼 클릭 후 버튼의 로딩 스피너 제거
    void answerCallbackQuery(String callbackQueryId) {
        if (!props.hasBot()) return;
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/answerCallbackQuery";
            telegramRestClient.post().uri(url)
                    .body(Map.of("callback_query_id", callbackQueryId))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("answerCallbackQuery 실패: {}", e.getMessage());
        }
    }
}
