package com.kista.api.market.adapter.out.internal;

import com.kista.api.market.application.port.output.CandleQueryPort;
import com.kista.api.market.domain.model.CandleUnavailableException;
import com.kista.shared.contract.broker.DailyCandleResponse;
import com.kista.shared.platform.internalapi.InternalApiErrorDetails;
import com.kista.shared.platform.web.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
class CandleQueryHttpAdapter implements CandleQueryPort {

    private static final String UNAVAILABLE_DETAIL = "증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요."; // 503 사용자 노출 고정 문구

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
                .onStatus(status -> status.value() == 422, CandleQueryHttpAdapter::throwCredentialInvalid)
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
                .onStatus(status -> status.value() == 422, CandleQueryHttpAdapter::throwCredentialInvalid)
                .body(new ParameterizedTypeReference<List<DailyCandleResponse>>() {});
    }

    // trading-core 503(증권사 장애)을 매핑된 예외로 복원 — 없으면 HttpServerErrorException이 root catch-all에서 500으로 뭉개지고 중복 보고된다
    private static void throwUnavailable(HttpRequest request,
                                         ClientHttpResponse response) {
        throw new CandleUnavailableException(InternalApiErrorDetails.detailOrDefault(response,
                UNAVAILABLE_DETAIL));
    }

    // trading-core 422 중 BROKER_CREDENTIAL_INVALID(서버 시세 조회용 증권사 키 무효)만 503으로 복원한다 — 공개 시세 API라 사용자가 고칠 수 없는 서버 장애다.
    // trading-core는 4xx를 보고하지 않아 키 만료를 놓치지 않도록 log.error로 남기고, detail("API 키가 유효하지 않습니다")은 사용자를 오도하므로 고정 문구를 쓴다.
    // 다른 코드의 422는 의미를 모르므로 기본 동작(HttpClientErrorException → root catch-all 보고)을 유지한다
    private static void throwCredentialInvalid(HttpRequest request,
                                               ClientHttpResponse response) throws IOException {
        byte[] body = response.getBody().readAllBytes();
        if (!ErrorCode.BROKER_CREDENTIAL_INVALID.name().equals(InternalApiErrorDetails.codeOrNull(body))) {
            throw HttpClientErrorException.create(response.getStatusCode(), response.getStatusText(),
                    response.getHeaders(), body, StandardCharsets.UTF_8);
        }
        log.error("캔들 조회용 증권사 키가 유효하지 않습니다: {}", request.getURI().getPath());
        throw new CandleUnavailableException(UNAVAILABLE_DETAIL);
    }
}
