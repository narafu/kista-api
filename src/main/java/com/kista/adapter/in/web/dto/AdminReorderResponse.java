package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminReorderResult;

import java.util.UUID;

// 관리자 재주문 응답 DTO
public record AdminReorderResponse(
        UUID userId,
        UUID accountId,
        UUID strategyId,
        UUID sourceOrderId,
        String originalStatus,
        String resultingStatus,      // PLANNED / PLACED / FAILED
        String newOrderExternalId    // IMMEDIATE 즉시 접수 성공 시만 non-null
) {
    public static AdminReorderResponse from(AdminReorderResult result) {
        return new AdminReorderResponse(
                result.userId(),
                result.accountId(),
                result.strategyId(),
                result.sourceOrderId(),
                result.originalStatus().name(),
                result.resultingStatus().name(),
                result.newOrderExternalId()
        );
    }
}
