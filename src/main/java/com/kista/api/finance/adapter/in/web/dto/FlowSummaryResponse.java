package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.FlowSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

public record FlowSummaryResponse(
        @Schema(description = "기간 내 합계 (원화 정수)", example = "3500000")
        long total,
        @Schema(description = "기간 내 거래 건수", example = "12")
        int count,
        @Schema(description = "전월 전체 합 (월간 모드만)")
        @Nullable Long previousTotal,
        @Schema(description = "전년 동기간 합 (연간 모드만)")
        @Nullable Long previousYearTotal,
        @Schema(description = "선택 연도 월평균 (원화 정수, 반올림)", example = "3200000")
        long yearlyAverage,
        @Schema(description = "수입 - 소비 - 저축 (수입 유형만)")
        @Nullable Long remaining,
        @Schema(description = "같은 기간 수입 대비 비율 0~1, scale 4 (수입 외 유형이고 수입 합계가 0보다 클 때만)", example = "0.4521")
        @Nullable BigDecimal incomeRatio
) {
    public static FlowSummaryResponse from(FlowSummary s) {
        return new FlowSummaryResponse(s.total(), s.count(), s.previousTotal(), s.previousYearTotal(),
                s.yearlyAverage(), s.remaining(), s.incomeRatio());
    }
}
