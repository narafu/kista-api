package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.PrivacyQueryPort;
import com.kista.admin.application.usecase.AdminPrivacyTradeUseCase;
import com.kista.contract.privacy.FidaOrderRequest;
import com.kista.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.contract.privacy.PrivacyOrderAddRequest;
import com.kista.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.contract.privacy.PrivacyTradeBaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

// 클래스 레벨 @Transactional 제거됨 — privacyQueryPort가 HTTP 어댑터(내부 API 호출)라 DB 트랜잭션
// 안에서 호출하면 커넥션 풀 자기잠금 위험이 있다(AdminQueryService와 동일 사유, constraints.md
// "@Transactional 내부 외부 시스템 호출 금지" 참고). 예전엔 privacyTradePort가 in-process 호출이라
// 안전했으나 이번 전환으로 더 이상 그렇지 않다.
@Service
@RequiredArgsConstructor
class AdminPrivacyTradeService implements AdminPrivacyTradeUseCase {

    // privacy.application.usecase.PrivacyUseCase/PrivacyTradePort 직접 주입 대신 admin이 정의한
    // 단일 포트로 조회+쓰기를 위임 — 등록(POST /api/internal/fida-orders 재사용)·수정 모두 이 포트 경유
    private final PrivacyQueryPort privacyQueryPort;
    private final AuditLogPort auditLogPort;

    @Override
    public CreateResult createBase(UUID adminId, FidaOrderRequest command) {
        PrivacyQueryPort.CreateBaseResult result = privacyQueryPort.createBase(command);
        auditLogPort.log(adminId, "PRIVACY_BASE_CREATE", "PRIVACY_TRADE_BASE", result.view().id(),
                Map.of("releaseDate", command.releaseDate().toString(),
                        "ticker", command.ticker().name(),
                        "created", String.valueOf(result.created())));
        return new CreateResult(result.view(), result.created());
    }

    @Override
    public PrivacyTradeBaseResponse updateBase(UUID adminId, UUID baseId, PrivacyBaseUpdateRequest command) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.updateBase(baseId, command);
        auditLogPort.log(adminId, "PRIVACY_BASE_UPDATE", "PRIVACY_TRADE_BASE", baseId,
                Map.of("currentCycleStart", command.currentCycleStart().toString(),
                        "currentCycleRealizedPnl", command.currentCycleRealizedPnl().toString(),
                        "holdings", String.valueOf(command.holdings())));
        return updated;
    }

    @Override
    public PrivacyTradeBaseResponse updateOrder(UUID adminId, UUID baseId, UUID orderId, PrivacyOrderUpdateRequest command) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.updateOrder(baseId, orderId, command);
        auditLogPort.log(adminId, "PRIVACY_ORDER_UPDATE", "PRIVACY_TRADE_BASE_ORDER", orderId,
                Map.of("baseId", baseId.toString(),
                        "price", command.price().toString(),
                        "quantity", String.valueOf(command.quantity())));
        return updated;
    }

    @Override
    public PrivacyTradeBaseResponse addOrder(UUID adminId, UUID baseId, PrivacyOrderAddRequest command) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.addOrder(baseId, command);
        auditLogPort.log(adminId, "PRIVACY_ORDER_ADD", "PRIVACY_TRADE_BASE_ORDER", baseId,
                Map.of("baseId", baseId.toString(),
                        "direction", command.direction().name(),
                        "orderType", command.orderType().name(),
                        "price", command.price().toString(),
                        "quantity", String.valueOf(command.quantity())));
        return updated;
    }

    @Override
    public PrivacyTradeBaseResponse deleteOrder(UUID adminId, UUID baseId, UUID orderId) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.deleteOrder(baseId, orderId);
        auditLogPort.log(adminId, "PRIVACY_ORDER_DELETE", "PRIVACY_TRADE_BASE_ORDER", orderId,
                Map.of("baseId", baseId.toString()));
        return updated;
    }
}
