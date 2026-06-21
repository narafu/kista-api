package com.kista.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.port.out.CryptoFearGreedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

// https://api.alternative.me/fng/ — Crypto Fear & Greed Index
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoFearGreedAdapter implements CryptoFearGreedPort {

    private static final String URL = "https://api.alternative.me/fng/";

    private final RestTemplate fearGreedRestTemplate;

    @Override
    public CryptoFearGreedData fetch() {
        FngResponse response = fearGreedRestTemplate.getForObject(URL, FngResponse.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("Crypto Fear & Greed Index API 응답이 비어있음");
        }
        FngEntry entry = response.data().getFirst();
        int value = Integer.parseInt(entry.value());
        FearGreedRating rating = FearGreedRating.fromLabel(entry.valueClassification());
        log.debug("Crypto F&G: value={}, rating={}", value, rating);
        return new CryptoFearGreedData(value, rating);
    }

    // alternative.me API 응답 구조
    record FngResponse(@JsonProperty("data") List<FngEntry> data) {}

    record FngEntry(
            @JsonProperty("value") String value,
            @JsonProperty("value_classification") String valueClassification,
            @JsonProperty("timestamp") String timestamp
    ) {}
}
