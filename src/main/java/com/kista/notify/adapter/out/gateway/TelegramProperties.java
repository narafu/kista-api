package com.kista.notify.adapter.out.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String chatId) {
    public boolean hasBot() {
        return botToken != null && !botToken.isBlank();
    }
}
