package com.kista.tradingcore.stats.adapter.out.frankfurter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.tradingcore.stats.application.port.output.HistoricalFxRatePort;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static java.util.stream.Collectors.toMap;

// Frankfurter(ECB 기준환율) 공개 API — 무인증, 영업일 단위 USD 기준 KRW 환율
@Component
class FrankfurterFxRateAdapter implements HistoricalFxRatePort {

    private static final String BASE_URL = "https://api.frankfurter.dev/v1";

    private final RestClient restClient;

    FrankfurterFxRateAdapter(RestClient statsFrankfurterRestClient) {
        this.restClient = statsFrankfurterRestClient;
    }

    @Override
    public Map<LocalDate, BigDecimal> getUsdKrwRates(LocalDate from, LocalDate to) {
        RatesResponse res = restClient.get()
                .uri(BASE_URL + "/{from}..{to}?base=USD&symbols=KRW", from, to)
                .retrieve().body(RatesResponse.class);
        if (res == null || res.rates() == null) return Map.of();
        // 날짜별 응답에서 KRW 환율만 추출 (영업일 원본 유지)
        return res.rates().entrySet().stream()
                .collect(toMap(Map.Entry::getKey, e -> e.getValue().get("KRW")));
    }

    record RatesResponse(@JsonProperty("rates") Map<LocalDate, Map<String, BigDecimal>> rates) {}
}
