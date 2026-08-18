package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.MonthlyClosing;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public record MonthlyClosingResponse(
        @Schema(description = "연월", example = "2026-08")
        String month,
        @Schema(description = "완료 여부")
        boolean completed,
        @Schema(description = "완료 전환 시각 (미완료면 null)")
        Instant closedAt
) {
    public static MonthlyClosingResponse from(MonthlyClosing c) {
        return new MonthlyClosingResponse(c.month(), c.completed(), c.closedAt());
    }
}
