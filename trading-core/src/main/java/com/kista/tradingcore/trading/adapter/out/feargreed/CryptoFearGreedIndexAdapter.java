package com.kista.tradingcore.trading.adapter.out.feargreed;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.trading.application.port.output.CryptoFearGreedIndexPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

// alternative.me 코인 공포탐욕지수 최신값 — data[0].value(문자열) 사용
@Slf4j
@Component
@RequiredArgsConstructor
class CryptoFearGreedIndexAdapter implements CryptoFearGreedIndexPort {

    private static final String URL = "https://api.alternative.me/fng/?limit=1";

    private final RestClient fearGreedRestClient;

    @Override
    public Optional<BigDecimal> latest() {
        try {
            AltResponse response = fearGreedRestClient.get().uri(URL).retrieve().body(AltResponse.class);
            if (response == null || response.data() == null || response.data().isEmpty()) {
                log.warn("[DCA] 코인 공포탐욕지수 값 없음");
                return Optional.empty();
            }
            return Optional.of(new BigDecimal(response.data().getFirst().value()).setScale(2, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            log.warn("[DCA] 코인 공포탐욕지수 조회 실패", e);
            return Optional.empty();
        }
    }

    // alternative.me 응답 중 data 배열만 사용
    record AltResponse(@JsonProperty("data") List<Item> data) {}

    // value: 지수 값(문자열, 예: "27")
    record Item(@JsonProperty("value") String value) {}
}
