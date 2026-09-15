package com.kista.admin.application.service;

import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.admin.application.usecase.AdminReorderUseCase;
import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// 관리자 재주문 접수/취소 — 실제 로직은 trading-core 내부 API로 이관됨, 여기는 요청/응답 전달 + 감사 로그만 담당
@Service
@RequiredArgsConstructor
class AdminReorderService implements AdminReorderUseCase {

    private static final String AUDIT_ACTION = "REORDER"; // 감사 로그 액션 코드
    private static final String AUDIT_TARGET_TYPE = "ORDER";

    private final TradingCommandPort tradingCommandPort;
    private final AuditLogPort auditLogPort;

    @Override
    public AdminReorderResult reorder(UUID adminId, AdminReorderCommand command) {
        AdminReorderResult result = tradingCommandPort.reorder(command);

        auditLogPort.log(adminId, AUDIT_ACTION, AUDIT_TARGET_TYPE, result.sourceOrderId(),
                auditPayload(command, result));

        return result;
    }

    // 실제 브로커 주문을 유발하는 감사 대상 액션이므로 원본/변경 주문 내역을 전부 기록한다
    // (oldStatus 키명은 리팩토링 전 감사 로그 레코드와 호환 유지 위해 그대로 존치)
    private static Map<String, Object> auditPayload(AdminReorderCommand command, AdminReorderResult result) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("timing", command.timing().name());
        payload.put("strategyId", command.strategyId().toString());
        payload.put("accountId", command.accountId().toString());
        payload.put("orderId", result.sourceOrderId().toString());
        payload.put("oldStatus", result.originalStatus().name());
        payload.put("oldPrice", result.oldPrice().toPlainString());
        payload.put("oldQuantity", result.oldQuantity());
        payload.put("newDirection", result.newDirection().name());
        payload.put("newPrice", command.price().toPlainString());
        payload.put("newQuantity", command.quantity());
        payload.put("resultingStatus", result.resultingStatus().name());
        if (command.memo() != null && !command.memo().isBlank()) {
            payload.put("memo", command.memo());
        }
        return payload;
    }
}
