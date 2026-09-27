package com.kista.notify.adapter.out.gateway;

import com.kista.notify.application.port.output.NotifyPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정

    @Override
    public void notifyError(Exception e) {
        send(String.format("<b>⚠️ 관리자 알림</b>%n%s", e.getMessage()));
    }

    @Override
    public void notifyInfo(String message) {
        send(message); // 일반 정보성 메시지 그대로 전송
    }

    // 관리자 봇 채팅방으로 단순 메시지 전송
    private void send(String text) {
        telegramHttpClient.sendMessage(props.chatId(), text, props.botToken());
    }
}
