package com.kista.api.benchmark.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.TimeZones;
import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.application.port.output.IndexPriceFeedPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AlpacaIndexPriceAdapter implements IndexPriceFeedPort {

    private static final LocalTime BAR_SETTLED_AT = LocalTime.of(16, 16); // 뉴욕 시각 — 정규 마감 16:00 + 통합 시세 지연 15분 뒤라야 당일 봉이 확정

    private final RestClient alpacaRestClient;
    private final AlpacaProperties alpacaProperties;
    private final Clock clock; // 당일 봉 확정 여부 판단 기준 — 테스트에서 Clock.fixed 주입

    // Alpaca Market Data /v2/stocks/{symbol}/bars — 일봉 limit 10000이면 약 40년치라 페이지네이션 불필요
    // feed=sip(통합 종가) — iex 종가는 공식 종가와 다르다. 무료 플랜은 최근 15분에 걸린 end를 주면 sip이 403이라
    // end 없이 받고 to 이하·확정된 봉만 코드에서 고른다(MarketDataClient와 같은 방식)
    @Override
    public List<IndexPrice> fetchDailyCloses(String symbol, LocalDate from, LocalDate to) {
        String url = UriComponentsBuilder
                .fromUriString(alpacaProperties.dataBaseUrl() + "/v2/stocks/" + symbol + "/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", from.toString())
                .queryParam("adjustment", "split")
                .queryParam("feed", "sip")
                .queryParam("limit", 10000)
                .toUriString();

        BarsResponse response = alpacaRestClient.get()
                .uri(url)
                .header("APCA-API-KEY-ID", alpacaProperties.apiKey())
                .header("APCA-API-SECRET-KEY", alpacaProperties.apiSecret())
                .retrieve()
                .body(BarsResponse.class);
        List<Bar> bars = response != null && response.bars() != null ? response.bars() : List.of();
        log.info("{} 지수 종가 {}건 수신 ({} ~ {})", symbol, bars.size(), from, to);
        // 뉴욕 오늘 봉은 16:16 전이면 진행 중이라 버린다 — 조기 마감일도 보수적으로 같은 시각을 쓰고, 버린 날은 다음 동기화(from=최대일+1)가 받는다
        Instant now = clock.instant();
        LocalDate nyToday = LocalDate.ofInstant(now, TimeZones.NY);
        boolean todayUnsettled = now.isBefore(nyToday.atTime(BAR_SETTLED_AT).atZone(TimeZones.NY).toInstant());
        return bars.stream()
                .map(bar -> new IndexPrice(
                        symbol,
                        Instant.parse(bar.t()).atZone(TimeZones.NY).toLocalDate(),
                        bar.c()))
                .filter(price -> !price.tradeDate().isAfter(to))
                .filter(price -> !(todayUnsettled && price.tradeDate().equals(nyToday)))
                .toList();
    }

    record Bar(@JsonProperty("t") String t, @JsonProperty("o") BigDecimal o, @JsonProperty("h") BigDecimal h,
               @JsonProperty("l") BigDecimal l, @JsonProperty("c") BigDecimal c, @JsonProperty("v") Long v) {}

    record BarsResponse(@JsonProperty("bars") List<Bar> bars,
                        @JsonProperty("next_page_token") String nextPageToken) {}
}
