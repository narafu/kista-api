package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.CancelResult;
import io.swagger.v3.oas.annotations.media.Schema;

public record CancelOrdersResponse(
        @Schema(description = "취소 접수 성공 건수")
        int cancelledCount, // 취소 접수 성공 건수
        @Schema(description = "취소 실패 건수 (이미 체결되었거나 증권사 오류)")
        int failedCount,    // 취소 실패 건수 (이미 체결되었거나 증권사 오류)
        @Schema(description = "취소 실패 중 증권사 자격증명 오류(키 만료·철회) 건수 — 재시도해도 풀리지 않아 계좌 키 재등록 필요")
        int credentialFailedCount // failedCount 중 자격증명 오류 건수
) {
    public static CancelOrdersResponse from(CancelResult result) {
        return new CancelOrdersResponse(result.cancelledCount(), result.failedCount(), result.credentialFailedCount());
    }
}
