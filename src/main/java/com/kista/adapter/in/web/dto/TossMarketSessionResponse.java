package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossMarketSession.SessionHours;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// Toss /api/v1/market-calendar/US 응답 DTO
// isOpen — regularMarket != null 이면 정규장 개장일
public record TossMarketSessionResponse(
    LocalDate      date,
    boolean        isOpen,
    SessionResponse preMarket,
    SessionResponse regularMarket,
    SessionResponse afterMarket
) {
    public static TossMarketSessionResponse from(TossMarketSession s) {
        return new TossMarketSessionResponse(
                s.date(),
                s.isOpen(),
                SessionResponse.from(s.preMarket()),
                SessionResponse.from(s.regularMarket()),
                SessionResponse.from(s.afterMarket())
        );
    }

    public static List<TossMarketSessionResponse> fromList(List<TossMarketSession> sessions) {
        return sessions.stream().map(TossMarketSessionResponse::from).toList();
    }

    public record SessionResponse(OffsetDateTime startTime, OffsetDateTime endTime) {
        static SessionResponse from(SessionHours h) {
            if (h == null) return null;
            return new SessionResponse(h.startTime(), h.endTime());
        }
    }
}
