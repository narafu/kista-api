package com.kista.api.market.adapter.out.internal;

import com.kista.api.market.application.port.output.CandleQueryPort;
import com.kista.shared.contract.broker.DailyCandleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
class CandleQueryHttpAdapter implements CandleQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<DailyCandleResponse> latestDailyCandles(String symbol, int count) {
        return internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/broker/candles/latest")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", "1d")
                        .queryParam("count", count)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<DailyCandleResponse>>() {});
    }

    @Override
    public List<DailyCandleResponse> dailyCandles(String symbol, LocalDate from, LocalDate to) {
        return internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/broker/candles/range")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", "1d")
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<DailyCandleResponse>>() {});
    }
}
