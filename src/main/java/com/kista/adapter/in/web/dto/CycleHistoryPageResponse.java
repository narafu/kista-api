package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.strategy.CycleHistoryPage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 거래내역 페이지 응답 — items + 커서 기반 다음 페이지 정보
public record CycleHistoryPageResponse(
        @Schema(description = "거래내역 항목 목록")
        List<CycleHistoryResponse> items,
        @Schema(description = "다음 페이지 조회용 커서 (마지막 페이지면 null)")
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor, // null이면 마지막 페이지
        @Schema(description = "다음 페이지 존재 여부")
        boolean hasMore
) {
    public static CycleHistoryPageResponse from(CycleHistoryPage page) {
        return new CycleHistoryPageResponse(
                page.items().stream().map(CycleHistoryResponse::from).toList(),
                page.nextCursor() != null ? page.nextCursor().toString() : null,
                page.hasMore()
        );
    }
}
