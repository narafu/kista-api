package com.kista.tradingcore.web;

import com.kista.shared.kernel.AppErrorRaisedEvent;
import com.kista.shared.platform.web.ExceptionReporter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// trading-core 프로세스의 예외 보고 — app_error_logs가 root 소유라 AppErrorRaisedEvent를 발행하고
// AppErrorStreamPublisher가 Redis Stream(stream:app.error)으로 root에 전달한다(스택 30줄 truncate는 저장 측)
@Component
@RequiredArgsConstructor
class TradingExceptionReporter implements ExceptionReporter {

    private final ApplicationEventPublisher eventPublisher; // 오류 보고 이벤트 발행

    @Override
    public void report(Exception ex) {
        eventPublisher.publishEvent(AppErrorRaisedEvent.of(ex, TradingExceptionReporter.class.getSimpleName()));
    }
}
