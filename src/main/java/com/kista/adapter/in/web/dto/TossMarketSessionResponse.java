package com.kista.adapter.in.web.dto;

import com.kista.domain.model.toss.TossMarketSession;
import com.kista.domain.model.toss.TossMarketSession.SessionHours;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

// Toss /api/v1/market-calendar/US 응답 DTO
// isOpen — regularMarket != null 이면 정규장 개장일
public record TossMarketSessionResponse(
    @Schema(description = "날짜 (US 현지 기준)")
    LocalDate      date,
    @Schema(description = "정규장 개장일 여부 (regularMarket != null)")
    boolean        isOpen,
    @Schema(description = "프리마켓 세션 (휴장일이면 null)")
    SessionResponse preMarket,
    @Schema(description = "정규장 세션 (휴장일·주말이면 null)")
    SessionResponse regularMarket,
    @Schema(description = "애프터마켓 세션 (휴장일이면 null)")
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

    public record SessionResponse(
            @Schema(description = "세션 시작 시각 (ISO-8601 UTC)")
            OffsetDateTime startTime,
            @Schema(description = "세션 종료 시각 (ISO-8601 UTC)")
            OffsetDateTime endTime) {
        static SessionResponse from(SessionHours h) {
            if (h == null) return null;
            return new SessionResponse(h.startTime(), h.endTime());
        }
    }
}
