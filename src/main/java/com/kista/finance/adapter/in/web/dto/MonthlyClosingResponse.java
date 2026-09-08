package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.MonthlyClosing;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record MonthlyClosingResponse(
        @Schema(description = "연월", example = "2026-08")
        String month,
        @Schema(description = "완료 여부")
        boolean completed,
        @Schema(description = "완료 전환 시각 (미완료면 null)")
        Instant closedAt,
        // findMyScope는 개인 마감(null) ∪ 현재 그룹 마감을 함께 반환한다 — 같은 month에 2건이
        // 올 수 있어 클라이언트가 groupId로 자신의 활성 스코프 행을 골라야 서버 가드와 일치한다
        @Schema(description = "그룹 마감이면 그룹 ID, 개인 마감이면 null")
        UUID groupId
) {
    public static MonthlyClosingResponse from(MonthlyClosing c) {
        return new MonthlyClosingResponse(c.month(), c.completed(), c.closedAt(), c.groupId());
    }
}
