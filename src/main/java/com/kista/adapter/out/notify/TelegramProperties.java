package com.kista.adapter.out.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String chatId) {}
