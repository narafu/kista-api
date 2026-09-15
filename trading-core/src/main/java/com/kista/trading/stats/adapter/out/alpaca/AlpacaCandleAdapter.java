package com.kista.trading.stats.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.sharedkernel.DailyCandle;
import com.kista.sharedkernel.port.HistoricalCandlePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

// HistoricalCandlePort(sharedkernel)는 원래 root com.kista.stats.adapter.out.alpaca.AlpacaIndexPriceAdapter가
// IndexPriceFeedPort와 함께 구현했으나, root는 trading-core 정의 인터페이스를 구현할 수 없는 컴파일
// 경계(root→trading-core 단방향)라 own-type HTTP 브릿지가 필요해 보였다. 하지만 이 포트는 외부 API(Alpaca)
// 호출일 뿐 root 소유 상태가 아니므로, root를 거치는 브릿지보다 trading-core가 직접 Alpaca를 호출하는 쪽이
// 더 단순하다(BacktestEngine의 유일 소비처도 trading-core 내부). marketcalendar의 동명 AlpacaProperties는
// internal이라 재사용 불가(ModulithArchitectureTest 위반, 실측 확인) — 이 패키지 소유 AlpacaConfig/
// AlpacaProperties를 별도로 둔다(같은 API 키를 공유하는 "alpaca:" yml 블록은 그대로 재사용)
@Slf4j
@Component
@RequiredArgsConstructor
class AlpacaCandleAdapter implements HistoricalCandlePort {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final RestClient tradingStatsAlpacaRestClient;
    private final AlpacaProperties alpacaProperties;

    // root의 구 AlpacaIndexPriceAdapter.fetchDailyCandles와 동일 로직(수정주가 sip 피드, to 클램프) — 그대로 이관
    @Override
    public List<DailyCandle> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
        LocalDate latestAllowed = LocalDate.now(NEW_YORK).minusDays(1);
        LocalDate clampedTo = to.isAfter(latestAllowed) ? latestAllowed : to;
        if (clampedTo.isBefore(to)) {
            log.info("{} 과거 일봉 조회 to 클램프: 요청={} → 적용={}", symbol, to, clampedTo);
        }

        String url = UriComponentsBuilder
                .fromUriString(alpacaProperties.dataBaseUrl() + "/v2/stocks/" + symbol + "/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", from.toString())
                .queryParam("end", clampedTo.toString())
                .queryParam("adjustment", "all")
                .queryParam("feed", "sip")
                .queryParam("limit", 10000)
                .toUriString();

        BarsResponse response = tradingStatsAlpacaRestClient.get()
                .uri(url)
                .header("APCA-API-KEY-ID", alpacaProperties.apiKey())
                .header("APCA-API-SECRET-KEY", alpacaProperties.apiSecret())
                .retrieve()
                .body(BarsResponse.class);
        List<Bar> bars = response != null ? response.bars() : null;
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException(
                    "과거 일봉 응답이 비어있음: symbol=%s, from=%s, to=%s".formatted(symbol, from, clampedTo));
        }
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
