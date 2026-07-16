package com.kista.adapter.in.web.dto;

import com.kista.domain.model.admin.AdminReorderResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// 관리자 재주문 응답 DTO
public record AdminReorderResponse(
        @Schema(description = "대상 사용자 ID")
        UUID userId,
        @Schema(description = "대상 계좌 ID")
        UUID accountId,
        @Schema(description = "대상 전략 ID")
        UUID strategyId,
        @Schema(description = "원본 주문 ID")
        UUID sourceOrderId,
        @Schema(description = "원본 주문 상태", example = "PLANNED")
        String originalStatus,
        @Schema(description = "재주문 처리 결과 상태", example = "PLACED")
        String resultingStatus,      // PLANNED / PLACED / FAILED
        @Schema(description = "신규 접수 주문의 브로커 측 ID (IMMEDIATE 즉시 접수 성공 시만 non-null)")
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
