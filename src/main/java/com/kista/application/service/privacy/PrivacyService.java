package com.kista.application.service.privacy;

import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.port.in.PrivacyUseCase;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
class PrivacyService implements PrivacyUseCase {

    private final PrivacyTradePort privacyTradePort;
    private final NotifyPort notifyPort;
    private final PrivacyTradeValidationService validationService;

    @Override
    public PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command) {
        // FIDA 수신값은 KST 발행일 원본 — 변환 없이 그대로 검증·저장 (release_date는 거래일이 아님)
        PrivacyTradeValidationReport report = validationService.inspect(command);
        if (report.hasBlockingIssues()) {
            log.error("[FIDA] 기준 매매표 저장 차단: {}", report.summary());
            IllegalArgumentException exception = new IllegalArgumentException("[FIDA] " + report.summary());
            notifyPort.notifyError(exception);
            throw exception;
        }
        if (report.hasIssues()) {
            notifyPort.notifyInfo("[PRIVACY] 기준 매매표 경고: " + report.summary());
        }
        return privacyTradePort.saveBaseWithOrders(command);
    }
}
