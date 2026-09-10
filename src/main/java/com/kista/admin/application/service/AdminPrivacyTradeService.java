package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.usecase.AdminPrivacyTradeUseCase;
import com.kista.privacy.application.port.output.PrivacyTradePort;
import com.kista.privacy.application.usecase.PrivacyUseCase;
import com.kista.privacy.domain.model.FidaOrderCommand;
import com.kista.privacy.domain.model.PrivacyBaseUpdateCommand;
import com.kista.privacy.domain.model.PrivacyOrderUpdateCommand;
import com.kista.privacy.domain.model.PrivacyTradeBaseView;
import com.kista.privacy.domain.model.PrivacyTradeSaveResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class AdminPrivacyTradeService implements AdminPrivacyTradeUseCase {

    private final PrivacyUseCase privacyUseCase;       // 등록 — FIDA 수신 경로 재사용
    private final PrivacyTradePort privacyTradePort;   // 단건 조회·수정
    private final AuditLogPort auditLogPort;

    @Override
    public CreateResult createBase(UUID adminId, FidaOrderCommand command) {
        PrivacyTradeSaveResult result = privacyUseCase.executeFidaOrder(command);
        auditLogPort.log(adminId, "PRIVACY_BASE_CREATE", "PRIVACY_TRADE_BASE", result.id(),
                Map.of("releaseDate", command.releaseDate().toString(),
                        "ticker", command.ticker().name(),
                        "created", String.valueOf(result.created())));
        return new CreateResult(privacyTradePort.findByIdOrThrow(result.id()), result.created());
    }

    @Override
    public PrivacyTradeBaseView updateBase(UUID adminId, UUID baseId, PrivacyBaseUpdateCommand command) {
        PrivacyTradeBaseView updated = privacyTradePort.updateBase(baseId, command);
        auditLogPort.log(adminId, "PRIVACY_BASE_UPDATE", "PRIVACY_TRADE_BASE", baseId,
                Map.of("currentCycleStart", command.currentCycleStart().toString(),
                        "currentCycleRealizedPnl", command.currentCycleRealizedPnl().toString(),
                        "holdings", String.valueOf(command.holdings())));
        return updated;
    }

    @Override
    public PrivacyTradeBaseView updateOrder(UUID adminId, UUID baseId, UUID orderId, PrivacyOrderUpdateCommand command) {
        PrivacyTradeBaseView updated = privacyTradePort.updateOrder(baseId, orderId, command);
        auditLogPort.log(adminId, "PRIVACY_ORDER_UPDATE", "PRIVACY_TRADE_BASE_ORDER", orderId,
                Map.of("baseId", baseId.toString(),
                        "price", command.price().toString(),
                        "quantity", String.valueOf(command.quantity())));
        return updated;
    }
}
