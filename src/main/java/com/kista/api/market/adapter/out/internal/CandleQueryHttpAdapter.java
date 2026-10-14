package com.kista.api.market.adapter.out.internal;

import com.kista.api.market.application.port.output.CandleQueryPort;
import com.kista.api.market.domain.model.CandleUnavailableException;
import com.kista.shared.contract.broker.DailyCandleResponse;
import com.kista.shared.platform.internalapi.InternalApiErrorDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
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
                .onStatus(status -> status.value() == 503, CandleQueryHttpAdapter::throwUnavailable)
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
                .onStatus(status -> status.value() == 503, CandleQueryHttpAdapter::throwUnavailable)
                .body(new ParameterizedTypeReference<List<DailyCandleResponse>>() {});
    }

    // trading-core 503(증권사 장애)을 매핑된 예외로 복원 — 없으면 HttpServerErrorException이 root catch-all에서 500으로 뭉개지고 중복 보고된다
    private static void throwUnavailable(HttpRequest request,
                                         ClientHttpResponse response) {
        throw new CandleUnavailableException(InternalApiErrorDetails.detailOrDefault(response,
                "증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요."));
    }
}
