package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.DstInfo;
import io.swagger.v3.oas.annotations.media.Schema;

// 재주문 시점 가용성 응답 — UI 주문시점 셀렉터 활성화 판단
public record ReorderTimingAvailabilityResponse(
        @Schema(description = "AT_OPEN 접수 가능 여부 (개장 전에만 true)")
        boolean atOpen,     // AT_OPEN 접수 가능 (개장 전에만)
        @Schema(description = "AT_CLOSE 접수 가능 여부 (마감 전에만 true)")
        boolean atClose,    // AT_CLOSE 접수 가능 (마감 전에만)
        @Schema(description = "즉시 접수 가능 여부 (정규장 중에만 true)")
        boolean immediate   // 즉시 접수 가능 (정규장 중에만)
) {
    public static ReorderTimingAvailabilityResponse from(DstInfo.ReorderTimingAvailability avail) {
        return new ReorderTimingAvailabilityResponse(avail.atOpen(), avail.atClose(), avail.immediate());
    }
}
