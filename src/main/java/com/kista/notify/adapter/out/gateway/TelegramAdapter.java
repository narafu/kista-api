package com.kista.notify.adapter.out.gateway;

import com.kista.platform.telegram.TelegramHttpClient;
import com.kista.platform.telegram.TelegramProperties;
import com.kista.notify.application.port.output.NotifyPort;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramAdapter implements NotifyPort {

    private final TelegramHttpClient telegramHttpClient; // 공통 HTTP 전송 유틸
    private final TelegramProperties props;              // 관리자 봇 설정
    private final ApplicationEventPublisher eventPublisher; // 오류 보고 이벤트 발행 — admin 리스너가 app_error_logs에 저장

    @Override
    public void notifyError(Exception e) {
        // 발송 전에 오류 보고 이벤트를 먼저 발행 — 저장은 admin AppErrorRaisedListener가 담당
        // 리스너 예외가 관리자 텔레그램 발송을 막지 않도록 격리
        try {
            eventPublisher.publishEvent(AppErrorRaisedEvent.of(e, "TelegramAdapter"));
        } catch (Exception reportEx) {
            log.warn("오류 보고 이벤트 발행 실패: {}", reportEx.getMessage());
        }
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
