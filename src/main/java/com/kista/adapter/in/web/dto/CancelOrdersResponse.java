package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.CancelResult;

public record CancelOrdersResponse(
        int cancelledCount, // 취소 접수 성공 건수
        int failedCount     // 취소 실패 건수 (이미 체결되었거나 증권사 오류)
) {
    public static CancelOrdersResponse from(CancelResult result) {
        return new CancelOrdersResponse(result.cancelledCount(), result.failedCount());
    }
}
