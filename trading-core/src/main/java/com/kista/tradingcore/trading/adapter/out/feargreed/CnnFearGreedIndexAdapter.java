package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

// CNN 공포탐욕지수 최신값 조회 — 응답 최상위 fear_and_greed.score(조회 시점 현재값) 사용 (spec 6.2)
@Slf4j
@Component
@RequiredArgsConstructor
class CnnFearGreedIndexAdapter implements FearGreedIndexPort {

    private static final String URL = "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";

    private final RestClient fearGreedRestClient;

    @Override
    public Optional<BigDecimal> latest() {
        try {
            CnnResponse response = fearGreedRestClient.get().uri(URL).retrieve().body(CnnResponse.class);
            if (response == null || response.current() == null || response.current().score() == null) {
                log.warn("[DCA] CNN 공포탐욕지수 현재값 없음");
                return Optional.empty();
            }
            return Optional.of(BigDecimal.valueOf(response.current().score()).setScale(2, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            log.warn("[DCA] CNN 공포탐욕지수 조회 실패", e);
            return Optional.empty();
        }
    }

    // CNN 응답 중 fear_and_greed 블록만 사용
    record CnnResponse(@JsonProperty("fear_and_greed") Current current) {}

    // score: 조회 시점 지수
    record Current(@JsonProperty("score") Double score) {}
}
