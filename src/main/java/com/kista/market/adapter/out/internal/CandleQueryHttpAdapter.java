package com.kista.market.adapter.out.internal;

import com.kista.market.application.port.output.CandleQueryPort;
import com.kista.market.domain.model.TossDailyCandle;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
@RequiredArgsConstructor
class CandleQueryHttpAdapter implements CandleQueryPort {

    private final RestClient internalApiRestClient;

    @Override
    public List<TossDailyCandle> latestDailyCandles(String symbol, int count) {
        return internalApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/internal/broker/candles/latest")
                        .queryParam("symbol", symbol)
                        .queryParam("interval", "1d")
                        .queryParam("count", count)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<List<TossDailyCandle>>() {});
    }
}
