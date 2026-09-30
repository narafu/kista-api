package com.kista.privacy.application.service;

import com.kista.privacy.application.event.PrivacyAlertRaisedEvent;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyTradeSaveResult;
import com.kista.privacy.domain.model.PrivacyTradeValidationReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class PrivacyService implements PrivacyUseCase {

    private final PrivacyTradePort privacyTradePort;
    private final PrivacyTradeValidationService validationService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command) {
        // FIDA 수신값은 KST 발행일 원본 — 변환 없이 그대로 검증·저장 (release_date는 거래일이 아님)
        PrivacyTradeValidationReport report = validationService.inspect(command);
        if (report.hasBlockingIssues()) {
            String message = "[FIDA] " + report.summary();
            log.error("[FIDA] 기준 매매표 저장 차단: {}", report.summary());
            // 관리자 알림은 notify 모듈이 이벤트로 구독 — 발행 후 예외를 던져 저장을 차단한다(순서 고정)
            eventPublisher.publishEvent(new PrivacyAlertRaisedEvent(PrivacyAlertRaisedEvent.Severity.BLOCKING, message));
            throw new IllegalArgumentException(message);
        }
        if (report.hasIssues()) {
            // 경고는 저장을 막지 않고 알림만 — 발행 후 정상 저장 진행
            eventPublisher.publishEvent(new PrivacyAlertRaisedEvent(
                    PrivacyAlertRaisedEvent.Severity.WARNING, "[PRIVACY] 기준 매매표 경고: " + report.summary()));
        }
        return privacyTradePort.saveBaseWithOrders(command);
    }
}
