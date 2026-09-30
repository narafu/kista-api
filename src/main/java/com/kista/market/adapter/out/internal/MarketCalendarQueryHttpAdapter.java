package com.kista.market.adapter.out.internal;

import com.kista.contract.marketcalendar.MarketSessionResponse;
import com.kista.market.application.port.output.MarketCalendarQueryPort;
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
    public MarketSessionResponse currentSession() {
        return internalApiRestClient.get()
                .uri("/api/internal/marketcalendar/session")
                .retrieve()
                .body(MarketSessionResponse.class);
    }
}
