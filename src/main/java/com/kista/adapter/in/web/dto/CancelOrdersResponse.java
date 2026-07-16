package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.CancelResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CancelOrdersResponse(
        @Schema(description = "취소 접수 성공 건수")
        int cancelledCount, // 취소 접수 성공 건수
        @Schema(description = "취소 실패 건수 (이미 체결되었거나 증권사 오류)")
        int failedCount     // 취소 실패 건수 (이미 체결되었거나 증권사 오류)
) {
    public static CancelOrdersResponse from(CancelResult result) {
        return new CancelOrdersResponse(result.cancelledCount(), result.failedCount());
    }
}
