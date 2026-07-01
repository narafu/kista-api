package com.kista.application.service.privacy;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import com.kista.domain.model.privacy.PrivacyTradeValidationReport;
import com.kista.domain.port.in.PrivacyUseCase;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.PrivacyTradePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
class PrivacyService implements PrivacyUseCase {

    private final PrivacyTradePort privacyTradePort;
    private final NotifyPort notifyPort;
    private final PrivacyTradeValidationService validationService;

    @Override
    @Transactional(readOnly = true)
    public PrivacyCurrentBase getPrivacyCurrentBase() {
        // 오늘 이후 거래일 중 가장 미래의 기준가 반환
        return privacyTradePort.findCurrentBase()
                .orElseThrow(() -> new NoSuchElementException("오늘 이후 날짜의 기준 매매표가 없습니다"));
    }

    @Override
    public PrivacyTradeSaveResult executeFidaOrder(FidaOrderCommand command) {
        // FIDA는 UTC(=US 거래일) 일자 송신 → KST로 변환 후 도메인 전달
        // Persistence adapter가 다시 KST→UTC 변환하므로 원본 FIDA 일자가 DB에 정확히 저장됨
        FidaOrderCommand kstCommand = new FidaOrderCommand(
                TradeDateConverter.toKst(command.tradeDate()),
                command.ticker(),
                command.currentCycleStart(),
                command.currentCycleRealizedPnl(),
                command.avgPrice(),
                command.holdings(),
                command.orders()
        );
        PrivacyTradeValidationReport report = validationService.inspect(kstCommand);
        if (report.hasBlockingIssues()) {
            IllegalArgumentException exception = new IllegalArgumentException(report.summary());
            notifyPort.notifyError(exception);
            throw exception;
        }
        if (report.hasIssues()) {
            notifyPort.notifyInfo("[PRIVACY] 기준 매매표 경고: " + report.summary());
        }
        return privacyTradePort.saveBaseWithOrders(kstCommand);
    }
}
