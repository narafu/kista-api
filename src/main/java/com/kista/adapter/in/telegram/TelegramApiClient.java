package com.kista.adapter.in.telegram;

import com.kista.adapter.out.notify.TelegramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);
    private static final String API_BASE = "https://api.telegram.org";

    private final RestTemplate restTemplate;
    private final TelegramProperties props;

    TelegramApiClient(RestTemplate telegramRestTemplate, TelegramProperties props) {
        this.restTemplate = telegramRestTemplate;
        this.props = props;
    }

    void sendMessage(String chatId, String text) {
        if (props.botToken() == null || props.botToken().isBlank()) {
            log.warn("Telegram botToken 미설정 — 메시지 전송 생략");
            return;
        }
        try {
            String url = API_BASE + "/bot" + props.botToken() + "/sendMessage";
            restTemplate.postForObject(url, Map.of(
                    "chat_id", chatId,
                    "text", text,
                    "parse_mode", "HTML"), String.class);
        } catch (Exception e) {
            log.error("Telegram 메시지 전송 실패: {}", e.getMessage());
        }
    }
}
