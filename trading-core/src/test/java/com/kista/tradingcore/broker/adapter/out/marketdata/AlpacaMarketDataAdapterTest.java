package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.alpaca.AlpacaProperties;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.kista.shared.kernel.StrategyTicker.MAGX;
import static com.kista.shared.kernel.StrategyTicker.SOXL;
import static com.kista.shared.kernel.StrategyTicker.TQQQ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 응답 JSON은 findings/21 8절 실호출(2026-11-13)의 형태다. 고정 시계는 뉴욕 2026-01-05(월) 22:00
class AlpacaMarketDataAdapterTest {

    private static final String SNAPSHOTS = "https://data.test/v2/stocks/snapshots";
    private static final String BARS = "https://data.test/v2/stocks/bars";
    private static final LocalDate SESSION = LocalDate.of(2026, 1, 5); // 직전 거래일은 01-02(금), 01-01은 휴장

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://data.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final AlpacaMarketDataAdapter adapter = new AlpacaMarketDataAdapter(builder.build(),
            new AlpacaProperties(null, "test-key", "test-secret", "https://data.test"), FixedClockTestConfig.FIXED);

    private static RequestMatcher noQueryParam(String name) {
        return request -> assertThat(request.getURI().getQuery()).doesNotContain(name + "=");
    }

    @Test
    @DisplayName("현재가: 두 피드 중 체결 시각이 더 늦은 쪽을 종목별로 고르고 60초 동안 다시 부르지 않는다")
    void latestPrices_picksLaterTradePerTicker() {
        // SOXL은 iex가 더 최근, MAGX는 iex 최근 체결이 이틀 전이라 지연 통합 시세를 쓴다
        server.expect(ExpectedCount.once(), requestTo(startsWith(SNAPSHOTS)))
                .andExpect(queryParam("feed", "iex"))
                .andExpect(queryParam("symbols", "SOXL,MAGX"))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
                .andExpect(header("APCA-API-SECRET-KEY", "test-secret"))
                .andRespond(withSuccess("""
                        {"SOXL":{"latestTrade":{"t":"2026-11-13T15:55:36.114694956Z","x":"V","p":53.67,"s":100,"c":["@"],"i":1,"z":"B"},
                                 "dailyBar":{"t":"2026-11-13T05:00:00Z","o":55.1,"h":55.4,"l":53.2,"c":53.805,"v":1050000,"n":9000,"vw":54.1}},
                         "MAGX":{"latestTrade":{"t":"2026-11-11T19:25:01.5Z","x":"V","p":56.185,"s":1,"c":["@","I"],"i":2,"z":"B"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(SNAPSHOTS)))
                .andExpect(queryParam("feed", "delayed_sip"))
                .andRespond(withSuccess("""
                        {"SOXL":{"latestTrade":{"t":"2026-11-13T15:40:38Z","x":"P","p":53.81,"s":10,"c":["@"],"i":3,"z":"B"}},
                         "MAGX":{"latestTrade":{"t":"2026-11-13T15:32:06.25Z","x":"P","p":54.745,"s":5,"c":["@"],"i":4,"z":"B"}}}
                        """, MediaType.APPLICATION_JSON));

        Map<StrategyTicker, BigDecimal> first = adapter.latestPrices(List.of(SOXL, MAGX));
        Map<StrategyTicker, BigDecimal> second = adapter.latestPrices(List.of(SOXL, MAGX));

        assertThat(first).containsOnly(Map.entry(SOXL, new BigDecimal("53.67")), Map.entry(MAGX, new BigDecimal("54.745")));
        assertThat(second).isEqualTo(first);
        server.verify();
    }

    @Test
    @DisplayName("현재가: 한쪽 피드가 실패하면 다른 쪽만으로 답하고, 응답에 없는 종목은 키가 없다")
    void latestPrices_oneFeedFails_answersFromOther() {
        server.expect(requestTo(startsWith(SNAPSHOTS))).andExpect(queryParam("feed", "iex"))
                .andRespond(withSuccess("""
                        {"SOXL":{"latestTrade":{"t":"2026-11-13T15:55:36Z","p":53.67}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(SNAPSHOTS))).andExpect(queryParam("feed", "delayed_sip"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"subscription does not permit querying recent SIP data\"}"));

        assertThat(adapter.latestPrices(List.of(SOXL, TQQQ))).containsOnly(Map.entry(SOXL, new BigDecimal("53.67")));
        server.verify();
    }

    @Test
    @DisplayName("현재가: 두 피드가 모두 실패하면 대체값 없이 예외다")
    void latestPrices_bothFeedsFail_throws() {
        server.expect(ExpectedCount.twice(), requestTo(startsWith(SNAPSHOTS))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.latestPrices(List.of(SOXL))).isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("전일 종가: end 없이 받아 세션일보다 앞인 마지막 봉을 고른다 — 휴장일을 건너뛰고 세션일 봉은 버린다")
    void closesBefore_picksLastBarBeforeSession() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("symbols", "SOXL,TQQQ"))
                .andExpect(queryParam("timeframe", "1Day"))
                .andExpect(queryParam("feed", "sip"))
                .andExpect(queryParam("adjustment", "split"))
                .andExpect(queryParam("start", "2025-12-26"))
                .andExpect(noQueryParam("end"))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2025-12-31T05:00:00Z","o":60.1,"h":61.0,"l":59.0,"c":60.5,"v":90000000,"n":1,"vw":60.2},
                                         {"t":"2026-01-02T05:00:00Z","o":64.0,"h":66.0,"l":63.5,"c":65.53,"v":95300000,"n":1,"vw":65.0},
                                         {"t":"2026-01-05T05:00:00Z","o":55.1,"h":55.4,"l":53.2,"c":53.81,"v":95300000,"n":1,"vw":54.1}],
                                 "TQQQ":[{"t":"2026-01-02T05:00:00Z","o":59.0,"h":60.2,"l":58.8,"c":59.88,"v":36800000,"n":1,"vw":59.5}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        Map<StrategyTicker, BigDecimal> first = adapter.closesBefore(List.of(SOXL, TQQQ), SESSION);
        Map<StrategyTicker, BigDecimal> second = adapter.closesBefore(List.of(TQQQ), SESSION); // 그날 내내 캐시

        assertThat(first).containsOnly(Map.entry(SOXL, new BigDecimal("65.53")), Map.entry(TQQQ, new BigDecimal("59.88")));
        assertThat(second).containsOnly(Map.entry(TQQQ, new BigDecimal("59.88")));
        server.verify();
    }

    @Test
    @DisplayName("확정 일봉: end 없이 원주가로 받아 세션일 봉만 준다 — 봉이 없는 종목은 키가 없고 캐시하지 않는다")
    void confirmedBars_returnsSessionBarOnly_andDoesNotCacheMissing() {
        // 첫 응답: SOXL은 세션일 봉이 있고 TQQQ는 전일 봉만 있다(아직 확정 전)
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("symbols", "SOXL,TQQQ"))
                .andExpect(queryParam("feed", "sip"))
                .andExpect(queryParam("adjustment", "raw"))
                .andExpect(queryParam("start", "2026-01-05"))
                .andExpect(noQueryParam("end"))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":55.1,"h":55.4,"l":53.2,"c":53.81,"v":95300000,"n":1,"vw":54.1}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));
        // 두 번째 호출: SOXL은 캐시, 없던 TQQQ만 다시 묻는다
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("symbols", "TQQQ"))
                .andRespond(withSuccess("""
                        {"bars":{"TQQQ":[{"t":"2026-01-05T05:00:00Z","o":56.0,"h":56.5,"l":55.5,"c":56.08,"v":36800000,"n":1,"vw":56.1}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        Map<StrategyTicker, BrokerCandle> first = adapter.confirmedBars(List.of(SOXL, TQQQ), SESSION);
        Map<StrategyTicker, BrokerCandle> second = adapter.confirmedBars(List.of(SOXL, TQQQ), SESSION);

        BrokerCandle soxl = new BrokerCandle(SESSION, new BigDecimal("55.1"), new BigDecimal("55.4"),
                new BigDecimal("53.2"), new BigDecimal("53.81"), 95_300_000L);
        assertThat(first).containsOnly(Map.entry(SOXL, soxl));
        assertThat(second).containsOnlyKeys(SOXL, TQQQ);
        assertThat(second.get(TQQQ).close()).isEqualByComparingTo("56.08");
        server.verify();
    }

    @Test
    @DisplayName("확정 일봉: 마감+16분 전에 읽은 봉은 캐시하지 않는다 — 진행 중인 봉이 그날 내내 남지 않는다")
    void confirmedBars_beforeSettled_isNotCached() {
        // 뉴욕 2026-01-05 16:10 — 정규 마감 뒤지만 통합 시세 지연 15분이 지나지 않았다
        RestClient.Builder earlyBuilder = RestClient.builder().baseUrl("https://data.test");
        MockRestServiceServer earlyServer = MockRestServiceServer.bindTo(earlyBuilder).build();
        AlpacaMarketDataAdapter early = new AlpacaMarketDataAdapter(earlyBuilder.build(),
                new AlpacaProperties(null, "test-key", "test-secret", "https://data.test"),
                Clock.fixed(Instant.parse("2026-01-05T21:10:00Z"), TimeZones.KST));
        earlyServer.expect(ExpectedCount.twice(), requestTo(startsWith(BARS)))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":55.1,"h":55.4,"l":53.2,"c":53.70,"v":90000000,"n":1,"vw":54.1}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        early.confirmedBars(List.of(SOXL), SESSION);
        early.confirmedBars(List.of(SOXL), SESSION);

        earlyServer.verify();
    }

    @Test
    @DisplayName("일봉 이력: next_page_token을 끝까지 따라가 오름차순으로 합친다(지난 기간은 end를 보낸다)")
    void dailyCandles_range_followsPagination() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("symbols", "QQQ"))
                .andExpect(queryParam("adjustment", "split"))
                .andExpect(queryParam("start", "2024-01-01"))
                .andExpect(queryParam("end", "2024-01-05"))
                .andExpect(queryParam("sort", "asc"))
                .andExpect(noQueryParam("page_token"))
                .andRespond(withSuccess("""
                        {"bars":{"QQQ":[{"t":"2024-01-02T05:00:00Z","o":100.0,"h":105.5,"l":99.2,"c":104.3,"v":123456,"n":1,"vw":1}]},
                         "next_page_token":"UVFRfDIwMjQ="}
                        """, MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("page_token", "UVFRfDIwMjQ%3D"))
                .andRespond(withSuccess("""
                        {"bars":{"QQQ":[{"t":"2024-01-03T05:00:00Z","o":104.3,"h":110.0,"l":103.1,"c":108.7,"v":98765,"n":1,"vw":1}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<BrokerCandle> candles = adapter.dailyCandles("QQQ",
                DailyCandleQuery.range(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5), DailyCandleQuery.Adjustment.SPLIT));

        assertThat(candles).extracting(BrokerCandle::date)
                .containsExactly(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3));
        server.verify();
    }

    @Test
    @DisplayName("일봉 이력: 세션일 이전 N개는 내림차순으로 N개만 받아 오름차순으로 돌려준다 — 빈 결과는 캐시하지 않는다")
    void dailyCandles_lastBefore_takesNewestCountAscending() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(queryParam("adjustment", "all"))
                .andExpect(queryParam("end", "2026-01-04"))
                .andExpect(queryParam("sort", "desc"))
                .andExpect(queryParam("limit", "2"))
                .andRespond(withSuccess("{\"bars\":{},\"next_page_token\":null}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-02T05:00:00Z","o":64.0,"h":66.0,"l":63.5,"c":65.53,"v":95300000,"n":1,"vw":65.0},
                                         {"t":"2025-12-31T05:00:00Z","o":60.1,"h":61.0,"l":59.0,"c":60.5,"v":90000000,"n":1,"vw":60.2}]},
                         "next_page_token":"more"}
                        """, MediaType.APPLICATION_JSON));
        DailyCandleQuery query = DailyCandleQuery.lastBefore(SESSION, 2, DailyCandleQuery.Adjustment.ALL);

        List<BrokerCandle> empty = adapter.dailyCandles("SOXL", query);
        List<BrokerCandle> loaded = adapter.dailyCandles("SOXL", query);
        List<BrokerCandle> cached = adapter.dailyCandles("SOXL", query);

        assertThat(empty).isEmpty();
        assertThat(loaded).extracting(BrokerCandle::date).containsExactly(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2));
        assertThat(cached).isEqualTo(loaded);
        server.verify();
    }

    @Test
    @DisplayName("일봉 이력: 오늘까지 걸친 조회는 end를 보내지 않는다")
    void dailyCandles_upToToday_omitsEnd() {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(noQueryParam("end"))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":55.1,"h":55.4,"l":53.2,"c":53.81,"v":95300000,"n":1,"vw":54.1}]},
                         "next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.dailyCandles("SOXL", DailyCandleQuery.latest(1, DailyCandleQuery.Adjustment.SPLIT))).hasSize(1);
        server.verify();
    }
}
