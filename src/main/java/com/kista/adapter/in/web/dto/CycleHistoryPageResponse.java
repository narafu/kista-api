package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.tradingcycle.CycleHistoryPage;

import java.util.List;

// 거래내역 페이지 응답 — items + 커서 기반 다음 페이지 정보
public record CycleHistoryPageResponse(
        List<CycleHistoryResponse> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String nextCursor, // null이면 마지막 페이지
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
