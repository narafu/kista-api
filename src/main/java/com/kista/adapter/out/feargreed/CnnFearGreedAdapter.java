package com.kista.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.port.out.CnnFearGreedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

// https://production.dataviz.cnn.io/index/fearandgreed/graphdata — CNN Fear & Greed Index
@Slf4j
@Component
@RequiredArgsConstructor
class CnnFearGreedAdapter implements CnnFearGreedPort {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    private final RestTemplate fearGreedRestTemplate;

    @Override
    public CnnFearGreedData fetch() {
        CnnResponse response = fearGreedRestTemplate.getForObject(URL, CnnResponse.class);
        if (response == null || response.fearAndGreed() == null) {
            throw new IllegalStateException("CNN Fear & Greed Index API 응답이 비어있음");
        }
        CnnFearAndGreed data = response.fearAndGreed();
        int value = (int) Math.round(data.score());
        FearGreedRating rating = FearGreedRating.fromLabel(data.rating());
        log.debug("CNN F&G: value={}, rating={}", value, rating);
        return new CnnFearGreedData(value, rating);
    }

    // CNN API 응답 구조 (fear_and_greed 블록만 사용)
    record CnnResponse(@JsonProperty("fear_and_greed") CnnFearAndGreed fearAndGreed) {}

    record CnnFearAndGreed(
            @JsonProperty("score") double score,
            @JsonProperty("rating") String rating
    ) {}
}
