package com.kista.tradingcore.stats.adapter.out.alpaca;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.stats.domain.model.DailyCandle;
import com.kista.tradingcore.stats.application.port.output.HistoricalCandlePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

// 별도 AlpacaConfig/AlpacaProperties를 쓰는 이유 — marketcalendar의 동명 AlpacaProperties는 internal이라 재사용 불가(Modulith 경계)
@Slf4j
@Component
class AlpacaCandleAdapter implements HistoricalCandlePort {

    private final RestClient tradingStatsAlpacaRestClient;
    private final AlpacaProperties alpacaProperties;
    private final Clock clock; // 현재 시각 기준 — 테스트에서 Clock.fixed 주입

    AlpacaCandleAdapter(RestClient tradingStatsAlpacaRestClient, AlpacaProperties alpacaProperties, Clock clock) {
        this.tradingStatsAlpacaRestClient = tradingStatsAlpacaRestClient;
        this.alpacaProperties = alpacaProperties;
        this.clock = clock;
    }

    // root의 구 AlpacaIndexPriceAdapter.fetchDailyCandles와 동일 로직(수정주가 sip 피드, to 클램프) — 그대로 이관
    @Override
    public List<DailyCandle> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
        LocalDate latestAllowed = LocalDate.now(clock.withZone(TimeZones.NY)).minusDays(1);
        LocalDate clampedTo = to.isAfter(latestAllowed) ? latestAllowed : to;
        if (clampedTo.isBefore(to)) {
            log.info("{} 과거 일봉 조회 to 클램프: 요청={} → 적용={}", symbol, to, clampedTo);
        }

        String url = UriComponentsBuilder
                .fromUriString(alpacaProperties.dataBaseUrl() + "/v2/stocks/" + symbol + "/bars")
                .queryParam("timeframe", "1Day")
                .queryParam("start", from.toString())
                .queryParam("end", clampedTo.toString())
                .queryParam("adjustment", "split") // 분할만 수정 — 배당 수정(all)은 과거 가격을 낮춰 FIDA 기준표·평단가 입력(원가격)과 어긋난다
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
            log.warn("{} 과거 일봉 응답이 비어 있습니다 ({} ~ {})", symbol, from, clampedTo);
            throw new IllegalArgumentException("해당 기간의 과거 일봉 데이터가 없습니다.");
        }
        if (response.nextPageToken() != null) {
            log.warn("{} 과거 일봉 조회에 next_page_token 발생 — 페이지네이션 미구현이라 데이터 누락 가능", symbol);
        }
        log.info("{} 과거 일봉 {}건 수신 ({} ~ {})", symbol, bars.size(), from, clampedTo);
        return bars.stream()
                .map(bar -> new DailyCandle(
                        Instant.parse(bar.t()).atZone(TimeZones.NY).toLocalDate(),
                        bar.o(), bar.h(), bar.l(), bar.c()))
                .toList();
    }

    record Bar(String t, BigDecimal o, BigDecimal h, BigDecimal l, BigDecimal c) {}

    record BarsResponse(List<Bar> bars,
                        @JsonProperty("next_page_token") String nextPageToken) {}
}
