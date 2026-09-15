package com.kista.market.adapter.out.internal;

import com.kista.market.application.port.output.MarketCalendarQueryPort;
import com.kista.market.domain.model.MarketSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
class MarketCalendarQueryHttpAdapter implements MarketCalendarQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<LocalDate> findHolidaysForMonth(int year, int month) {
        return internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/marketcalendar/holidays")
                        .queryParam("year", year)
                        .queryParam("month", month)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<LocalDate>>() {});
    }

    @Override
    public boolean isMarketOpen(LocalDate date) {
        Boolean open = internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/marketcalendar/is-open")
                        .queryParam("date", date)
                        .build())
                .retrieve()
                .body(Boolean.class);
        return open != null && open;
    }

    @Override
    public SessionView currentSession() {
        SessionResponse response = internalApiRestClient.get()
                .uri("/api/internal/marketcalendar/session")
                .retrieve()
                .body(SessionResponse.class);
        return new SessionView(MarketSession.valueOf(response.session()), response.isDst());
    }

    record SessionResponse(String session, boolean isDst) {}
}
