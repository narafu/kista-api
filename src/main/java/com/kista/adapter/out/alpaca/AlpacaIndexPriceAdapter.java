package com.kista.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.stats.IndexPrice;
import com.kista.domain.port.out.HistoricalCandlePort;
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
public class AlpacaIndexPriceAdapter implements IndexPriceFeedPort, HistoricalCandlePort {

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

    // 백테스트용 과거 일봉 OHLC 조회 — 수정주가(adjustment=all) + sip 피드 사용
    // sip은 end가 현재로부터 15분 이상 과거여야 허용되므로(무료 플랜), to를 항상 어제 이전으로 클램프한다.
    // fetchDailyCloses(iex, 증분 동기화용)와 달리 이 메서드는 항상 과거 구간만 조회하므로 sip 사용 가능.
    @Override
    public List<DailyCandle> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
        // 403 방지: end는 반드시 어제 이전이어야 하므로 요청 to가 그보다 늦으면 클램프
        LocalDate latestAllowed = LocalDate.now(NEW_YORK).minusDays(1);
        LocalDate clampedTo = to.isAfter(latestAllowed) ? latestAllowed : to;
        if (clampedTo.isBefore(to)) {
            log.info("{} 과거 일봉 조회 to 클램프: 요청={} → 적용={}", symbol, to, clampedTo);
        }

        String url = UriComponentsBuilder
                .fromHttpUrl(alpacaProperties.dataBaseUrl() + "/v2/stocks/" + symbol + "/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", from.toString())
                .queryParam("end", clampedTo.toString())
                .queryParam("adjustment", "all")
                .queryParam("feed", "sip")
                .queryParam("limit", 10000)
                .toUriString();

        HttpHeaders headers = new HttpHeaders();
        headers.set("APCA-API-KEY-ID", alpacaProperties.apiKey());
        headers.set("APCA-API-SECRET-KEY", alpacaProperties.apiSecret());

        BarsResponse response = alpacaRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), BarsResponse.class).getBody();
        List<Bar> bars = response != null ? response.bars() : null;
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException(
                    "과거 일봉 응답이 비어있음: symbol=%s, from=%s, to=%s".formatted(symbol, from, clampedTo));
        }
        // next_page_token이 오면 10년치가 1회 호출로 다 들어온다는 실측 전제가 깨진 것 — 조기경보용 로그만
        if (response.nextPageToken() != null) {
            log.warn("{} 과거 일봉 조회에 next_page_token 발생 — 페이지네이션 미구현이라 데이터 누락 가능", symbol);
        }
        log.info("{} 과거 일봉 {}건 수신 ({} ~ {})", symbol, bars.size(), from, clampedTo);
        return bars.stream()
                .map(bar -> new DailyCandle(
                        Instant.parse(bar.t()).atZone(NEW_YORK).toLocalDate(),
                        bar.o(), bar.h(), bar.l(), bar.c()))
                .toList();
    }

    record Bar(@JsonProperty("t") String t, @JsonProperty("o") BigDecimal o, @JsonProperty("h") BigDecimal h,
               @JsonProperty("l") BigDecimal l, @JsonProperty("c") BigDecimal c, @JsonProperty("v") Long v) {}

    record BarsResponse(@JsonProperty("bars") List<Bar> bars,
                        @JsonProperty("next_page_token") String nextPageToken) {}
}
