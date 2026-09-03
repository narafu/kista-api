package com.kista.trading.application.service;

import com.kista.trading.application.event.TradingErrorEvent;
import com.kista.trading.domain.port.out.TradingErrorReportPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

// TradingErrorReportPort 구현체 — adapter.in(스케쥴러)이 application.event를 직접 참조하지 않도록 우회하는 경유지
@Component
@RequiredArgsConstructor
class TradingErrorReporter implements TradingErrorReportPort {

    private final ApplicationEventPublisher eventPublisher; // 관리자 오류 알림 이벤트 발행

    @Override
    public void reportError(Exception e) {
        eventPublisher.publishEvent(new TradingErrorEvent(null, e));
    }
}
