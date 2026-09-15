package com.kista.trading.notify.adapter.out.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

// root(:api)와 trading-core 두 프로세스가 같은 관리자 텔레그램 봇을 공유한다 — 둘 다 동일한
// TELEGRAM_BOT_TOKEN/TELEGRAM_CHAT_ID 환경변수를 읽어 같은 채팅방으로 발송한다(의도된 중복).
@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(String botToken, String chatId) {
    public boolean hasBot() {
        return botToken != null && !botToken.isBlank();
    }
}
