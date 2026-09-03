package com.kista.notify.adapter.out.gateway;

import com.kista.notify.domain.port.out.TelegramBotInfoPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
class TelegramBotInfoAdapter implements TelegramBotInfoPort {

    private static final String API_BASE = "https://api.telegram.org";

    private final RestClient telegramRestClient; // 빈 이름: telegramRestClient

    @Override
    @SuppressWarnings("unchecked")
    public String getUsername(String botToken) {
        try {
            String url = API_BASE + "/bot" + botToken + "/getMe";
            Map<String, Object> response = telegramRestClient.get().uri(url).retrieve().body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
                throw new IllegalArgumentException("유효하지 않은 Bot Token입니다");
            }
            // 응답 구조: { ok: true, result: { username: "narafu_kista_bot", ... } }
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            String username = (String) result.get("username");
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("봇 username을 가져올 수 없습니다");
            }
            return username;
        } catch (RestClientException e) {
            log.warn("Telegram getMe 실패: {}", e.getMessage());
            throw new IllegalArgumentException("유효하지 않은 Bot Token입니다: " + e.getMessage(), e);
        }
    }
}
