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
    // feed=iex 사용 — 무료 플랜에서 feed=sip은 end가 최소 15분 이전 시점이어야만 허용된다(실측 확인:
    // end=오늘 날짜로 조회 시 403 "subscription does not permit querying recent SIP data").
    // 이 메서드는 매일 end=오늘 날짜로 증분 동기화(MarketIndexPriceSyncService)에 쓰이므로 sip 사용 불가.
    // 과거 구간(2016-01-04~2020-07-27 sip, 그 이전 상장일까지 Yahoo Finance)은 1회성 스크립트로
    // 이미 DB에 백필했다 — end가 항상 과거인 백필에서만 sip이 유효하다.
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
