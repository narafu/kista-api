package com.kista.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.stats.IndexPrice;
import com.kista.domain.port.out.IndexPriceFeedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlpacaIndexPriceAdapter implements IndexPriceFeedPort {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestTemplate alpacaRestTemplate;
    private final AlpacaProperties alpacaProperties;

    // Alpaca Market Data /v2/stocks/{symbol}/bars — 일봉 limit 10000이면 약 40년치라 페이지네이션 불필요
    @Override
    public List<IndexPrice> fetchDailyCloses(String symbol, LocalDate from, LocalDate to) {
        String url = UriComponentsBuilder
                .fromHttpUrl(alpacaProperties.dataBaseUrl() + "/v2/stocks/" + symbol + "/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", from.toString())
                .queryParam("end", to.toString())
                .queryParam("adjustment", "split")
                .queryParam("feed", "iex")
                .queryParam("limit", 10000)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", alpacaProperties.apiKey());
        headers.set("APCA-API-SECRET-KEY", alpacaProperties.apiSecret());

        BarsResponse response = alpacaRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), BarsResponse.class).getBody();
        List<Bar> bars = response != null && response.bars() != null ? response.bars() : List.of();
        log.info("{} 지수 종가 {}건 수신 ({} ~ {})", symbol, bars.size(), from, to);
        return bars.stream()
                .map(bar -> new IndexPrice(
                        symbol,
                        Instant.parse(bar.t()).atZone(NEW_YORK).toLocalDate(),
                        bar.c()))
                .toList();
    }

    record Bar(@JsonProperty("t") String t, @JsonProperty("c") BigDecimal c) {}

    record BarsResponse(@JsonProperty("bars") List<Bar> bars,
                        @JsonProperty("next_page_token") String nextPageToken) {}
}
