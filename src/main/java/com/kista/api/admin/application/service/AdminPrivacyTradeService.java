package com.kista.api.admin.application.service;

import com.kista.api.admin.domain.model.PrivacyBaseCreateResult;
import com.kista.api.admin.application.port.output.AuditLogPort;
import com.kista.api.admin.application.port.output.PrivacyQueryPort;
import com.kista.shared.contract.privacy.FidaOrderRequest;
import com.kista.shared.contract.privacy.PrivacyBaseUpdateRequest;
import com.kista.shared.contract.privacy.PrivacyOrderAddRequest;
import com.kista.shared.contract.privacy.PrivacyOrderUpdateRequest;
import com.kista.shared.contract.privacy.PrivacyTradeBaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

// 클래스 레벨 @Transactional 제거됨 — privacyQueryPort가 HTTP 어댑터(내부 API 호출)라 DB 트랜잭션
// 안에서 호출하면 커넥션 풀 자기잠금 위험이 있다(AdminQueryService와 동일 사유, constraints.md
// "@Transactional 내부 외부 시스템 호출 금지" 참고). 예전엔 privacyTradePort가 in-process 호출이라
// 안전했으나 이번 전환으로 더 이상 그렇지 않다.
// 관리자 PRIVACY 기준 매매표 수동 보정 — 등록(FIDA 오류 대응)·마스터 필드 수정·개별 주문 가격·수량 수정
@Service
@RequiredArgsConstructor
public class AdminPrivacyTradeService {

    // privacy.application.service.PrivacyService/PrivacyTradePort 직접 주입 대신 admin이 정의한
    // 단일 포트로 조회+쓰기를 위임 — 등록(POST /api/internal/fida-orders 재사용)·수정 모두 이 포트 경유
    private final PrivacyQueryPort privacyQueryPort;
    private final AuditLogPort auditLogPort;

    public PrivacyBaseCreateResult createBase(UUID adminId, FidaOrderRequest command) {
        PrivacyBaseCreateResult result = privacyQueryPort.createBase(command);
        auditLogPort.log(adminId, "PRIVACY_BASE_CREATE", "PRIVACY_TRADE_BASE", result.view().id(),
                Map.of("releaseDate", command.releaseDate().toString(),
                        "ticker", command.ticker().name(),
                        "created", String.valueOf(result.created())));
        return result;
    }

    public PrivacyTradeBaseResponse updateBase(UUID adminId, UUID baseId, PrivacyBaseUpdateRequest command) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.updateBase(baseId, command);
        auditLogPort.log(adminId, "PRIVACY_BASE_UPDATE", "PRIVACY_TRADE_BASE", baseId,
                Map.of("currentCycleStart", command.currentCycleStart().toString(),
                        "currentCycleRealizedPnl", command.currentCycleRealizedPnl().toString(),
                        "holdings", String.valueOf(command.holdings())));
        return updated;
    }

    public PrivacyTradeBaseResponse updateOrder(UUID adminId, UUID baseId, UUID orderId, PrivacyOrderUpdateRequest command) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.updateOrder(baseId, orderId, command);
        auditLogPort.log(adminId, "PRIVACY_ORDER_UPDATE", "PRIVACY_TRADE_BASE_ORDER", orderId,
                Map.of("baseId", baseId.toString(),
                        "price", command.price().toString(),
                        "quantity", String.valueOf(command.quantity())));
        return updated;
    }

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

    public PrivacyTradeBaseResponse deleteOrder(UUID adminId, UUID baseId, UUID orderId) {
        PrivacyTradeBaseResponse updated = privacyQueryPort.deleteOrder(baseId, orderId);
        auditLogPort.log(adminId, "PRIVACY_ORDER_DELETE", "PRIVACY_TRADE_BASE_ORDER", orderId,
                Map.of("baseId", baseId.toString()));
        return updated;
    }
}
