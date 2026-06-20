package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossMarketSession;

import java.time.LocalDate;
import java.util.List;

public record TossMarketSessionResponse(
    LocalDate date,   // 날짜
    boolean   isOpen  // 개장 여부
) {
    public static TossMarketSessionResponse from(TossMarketSession session) {
        return new TossMarketSessionResponse(session.date(), session.isOpen());
    }

    public static List<TossMarketSessionResponse> fromList(List<TossMarketSession> sessions) {
        return sessions.stream().map(TossMarketSessionResponse::from).toList();
    }
}
