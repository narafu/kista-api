package com.kista.notify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.user.application.port.output.TelegramBotInfoPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// user의 TelegramBotInfoPort 구현 — getMe 호출·검증은 platform TelegramHttpClient가 담당(notify는 web.client 직접 사용 금지)
@Component
@RequiredArgsConstructor
class TelegramBotInfoAdapter implements TelegramBotInfoPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸

    @Override
    public String getUsername(String botToken) {
        return telegramHttpClient.getBotUsername(botToken);
    }
}
