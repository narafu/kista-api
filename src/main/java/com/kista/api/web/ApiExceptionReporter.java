package com.kista.api.web;

import com.kista.shared.kernel.AppErrorRaisedEvent;
import com.kista.shared.platform.web.ExceptionReporter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// root 프로세스의 예외 보고 — 공용 advice가 넘긴 예외를 AppErrorRaisedEvent로 발행하고 저장은 admin AppErrorRaisedListener가 한다(web은 admin 포트를 모른다)
@Component
@RequiredArgsConstructor
class ApiExceptionReporter implements ExceptionReporter {

    private final ApplicationEventPublisher eventPublisher; // 오류 보고 이벤트 발행

    @Override
    public void report(Exception ex) {
        eventPublisher.publishEvent(AppErrorRaisedEvent.of(ex, ApiExceptionReporter.class.getSimpleName()));
    }
}
