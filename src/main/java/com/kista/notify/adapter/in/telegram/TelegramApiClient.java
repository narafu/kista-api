package com.kista.notify.adapter.in.telegram;

import com.kista.notify.adapter.out.gateway.TelegramHttpClient;
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

    private final RestClient telegramRestClient; // 빈 이름: telegramRestClient — answerCallbackQuery 전용
    private final TelegramProperties props;
    private final TelegramHttpClient telegramHttpClient; // sendMessage 공통 로직 위임 (TelegramAdapter와 중복 제거)

    void sendMessage(String chatId, String text) {
        // 빈 토큰 가드는 TelegramHttpClient.sendMessage 내부에서 동일하게 수행
        telegramHttpClient.sendMessage(chatId, text, props.botToken());
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
