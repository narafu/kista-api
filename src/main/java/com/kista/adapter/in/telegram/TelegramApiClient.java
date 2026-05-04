package com.kista.adapter.in.telegram;

import com.kista.adapter.out.notify.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramApiClient {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate telegramRestTemplate; // 빈 이름: telegramRestTemplate
    private final TelegramProperties props;

    void sendMessage(String chatId, String text) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram botToken 미설정 — 메시지 전송 생략");
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            telegramRestTemplate.postForObject(url, Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML"), String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
