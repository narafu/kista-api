package com.kista.tradingcore.broker.adapter.out.marketdata;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.alpaca.AlpacaProperties;
import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.DailyCandleQuery;
import com.kista.tradingcore.broker.domain.model.MarketDataApiException;
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
class MarketDataClientTest {

    private static final String SNAPSHOTS = "https://data.test/v2/stocks/snapshots";
    private static final String BARS = "https://data.test/v2/stocks/bars";
    private static final LocalDate SESSION = LocalDate.of(2026, 1, 5); // 직전 거래일은 01-02(금), 01-01은 휴장

    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://data.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final MarketDataClient adapter = new MarketDataClient(builder.build(),
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
    @DisplayName("일봉: 서버 500이면 재시도 뒤에도 실패해 503 매핑 타입(MarketDataApiException)으로 원인과 함께 던진다")
    void dailyCandles_server500_throwsBrokerApiException() {
        server.expect(ExpectedCount.twice(), requestTo(startsWith(BARS))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> adapter.dailyCandles("SOXL", DailyCandleQuery.latest(5, DailyCandleQuery.Adjustment.RAW)))
                .isInstanceOf(MarketDataApiException.class)
                .isInstanceOf(com.kista.tradingcore.broker.domain.model.BrokerApiException.class)
                .hasCauseInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("현재가: 두 피드가 모두 실패하면 대체값 없이 예외다")
    void latestPrices_bothFeedsFail_throws() {
        server.expect(ExpectedCount.twice(), requestTo(startsWith(SNAPSHOTS))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.latestPrices(List.of(SOXL))).isInstanceOf(MarketDataApiException.class).hasCauseInstanceOf(RestClientException.class);
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
        MarketDataClient early = new MarketDataClient(earlyBuilder.build(),
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

    @Test
    @DisplayName("일봉 이력: 확정 봉 조회에서 마감+16분 전이면 진행 중인 오늘 봉을 빼고 그 앞 count개를 돌려준다")
    void dailyCandles_lastBefore_dropsUnsettledTodayBar() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://data.test");
        MockRestServiceServer srv = MockRestServiceServer.bindTo(b).build();
        MarketDataClient a = new MarketDataClient(b.build(),
                new AlpacaProperties(null, "test-key", "test-secret", "https://data.test"),
                Clock.fixed(Instant.parse("2026-01-05T20:40:00Z"), TimeZones.KST));
        srv.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(noQueryParam("end"))
                .andExpect(queryParam("limit", "3"))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":1,"h":1,"l":1,"c":50,"v":1,"n":1,"vw":1},{"t":"2026-01-02T05:00:00Z","o":1,"h":1,"l":1,"c":49,"v":1,"n":1,"vw":1},{"t":"2025-12-31T05:00:00Z","o":1,"h":1,"l":1,"c":48,"v":1,"n":1,"vw":1}]},"next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<BrokerCandle> candles = a.dailyCandles("SOXL", DailyCandleQuery.lastBefore(LocalDate.of(2026, 1, 6), 2, DailyCandleQuery.Adjustment.RAW));

        assertThat(candles).extracting(BrokerCandle::date).containsExactly(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2));
        srv.verify();
    }

    @Test
    @DisplayName("일봉 이력: 확정 봉 조회에서 마감+16분 뒤면 오늘 봉을 포함한다")
    void dailyCandles_lastBefore_keepsSettledTodayBar() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://data.test");
        MockRestServiceServer srv = MockRestServiceServer.bindTo(b).build();
        MarketDataClient a = new MarketDataClient(b.build(),
                new AlpacaProperties(null, "test-key", "test-secret", "https://data.test"),
                Clock.fixed(Instant.parse("2026-01-06T01:20:00Z"), TimeZones.KST));
        srv.expect(ExpectedCount.once(), requestTo(startsWith(BARS)))
                .andExpect(noQueryParam("end"))
                .andExpect(queryParam("limit", "2"))
                .andRespond(withSuccess("""
                        {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":1,"h":1,"l":1,"c":50,"v":1,"n":1,"vw":1},{"t":"2026-01-02T05:00:00Z","o":1,"h":1,"l":1,"c":49,"v":1,"n":1,"vw":1},{"t":"2025-12-31T05:00:00Z","o":1,"h":1,"l":1,"c":48,"v":1,"n":1,"vw":1}]},"next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<BrokerCandle> candles = a.dailyCandles("SOXL", DailyCandleQuery.lastBefore(LocalDate.of(2026, 1, 6), 2, DailyCandleQuery.Adjustment.RAW));

        assertThat(candles).extracting(BrokerCandle::date).containsExactly(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 5));
        srv.verify();
    }

    // 뉴욕 시각을 임의로 옮길 수 있는 시계
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(String instant) {
            this.now = Instant.parse(instant);
        }

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override public java.time.ZoneId getZone() { return TimeZones.KST; }
        @Override public Clock withZone(java.time.ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private static final String NOV_BARS = """
            {"bars":{"SOXL":[{"t":"2026-11-19T05:00:00Z","o":1,"h":1,"l":1,"c":58.32,"v":1,"n":1,"vw":1},
                             {"t":"2026-11-20T05:00:00Z","o":1,"h":1,"l":1,"c":49.19,"v":1,"n":1,"vw":1}]},"next_page_token":null}
            """;

    private MarketDataClient clientAt(Clock clock, MockRestServiceServer[] serverOut) {
        RestClient.Builder b = RestClient.builder().baseUrl("https://data.test");
        serverOut[0] = MockRestServiceServer.bindTo(b).build();
        return new MarketDataClient(b.build(), new AlpacaProperties(null, "test-key", "test-secret", "https://data.test"), clock);
    }

    @Test
    @DisplayName("closesBefore: 뉴욕 마감 정산 전에는 당일 봉을 전일 종가로 쓰지 않는다")
    void closesBefore_뉴욕_마감_정산_전에는_당일_봉을_전일_종가로_쓰지_않는다() {
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        MarketDataClient c = clientAt(Clock.fixed(Instant.parse("2026-11-20T20:50:00Z"), TimeZones.KST), srv); // 뉴욕 15:50
        srv[0].expect(ExpectedCount.once(), requestTo(startsWith(BARS))).andRespond(withSuccess(NOV_BARS, MediaType.APPLICATION_JSON));

        assertThat(c.closesBefore(List.of(SOXL), LocalDate.of(2026, 11, 23))).containsOnly(Map.entry(SOXL, new BigDecimal("58.32")));
        srv[0].verify();
    }

    @Test
    @DisplayName("closesBefore: 정산 전 결과는 캐시하지 않는다")
    void closesBefore_정산_전_결과는_캐시하지_않는다() {
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        MutableClock clock = new MutableClock("2026-11-20T20:50:00Z"); // 뉴욕 15:50
        MarketDataClient c = clientAt(clock, srv);
        srv[0].expect(ExpectedCount.twice(), requestTo(startsWith(BARS))).andRespond(withSuccess(NOV_BARS, MediaType.APPLICATION_JSON));
        LocalDate monday = LocalDate.of(2026, 11, 23);

        BigDecimal before = c.closesBefore(List.of(SOXL), monday).get(SOXL);
        clock.set("2026-11-21T01:20:00Z"); // 뉴욕 20:20 — 정산 뒤
        BigDecimal after = c.closesBefore(List.of(SOXL), monday).get(SOXL);

        assertThat(before).isEqualByComparingTo("58.32");
        assertThat(after).isEqualByComparingTo("49.19");
        srv[0].verify();
    }

    @Test
    @DisplayName("latestCloses: 뉴욕 다음날을 세션일로 쓴다")
    void latestCloses_뉴욕_다음날을_세션일로_쓴다() {
        MockRestServiceServer[] srv = new MockRestServiceServer[1];
        MarketDataClient c = clientAt(Clock.fixed(Instant.parse("2026-11-20T22:00:00Z"), TimeZones.KST), srv); // 뉴욕 17:00
        srv[0].expect(ExpectedCount.once(), requestTo(startsWith(BARS))).andRespond(withSuccess(NOV_BARS, MediaType.APPLICATION_JSON));

        assertThat(c.latestCloses(List.of(SOXL))).containsOnly(Map.entry(SOXL, new BigDecimal("49.19")));
        srv[0].verify();
    }

    @Test
    @DisplayName("requireLatestClose: 키가 없으면 예외")
    void requireLatestClose_키가_없으면_예외() {
        server.expect(requestTo(startsWith(BARS))).andRespond(withSuccess("{\"bars\":{},\"next_page_token\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.requireLatestClose(SOXL))
                .isInstanceOf(IllegalStateException.class).hasMessage("SOXL 종가를 조회하지 못했습니다.");
    }

    @Test
    @DisplayName("requireConfirmedClose: 봉이 없으면 예외, 있으면 종가")
    void requireConfirmedClose_봉이_없으면_예외() {
        server.expect(requestTo(startsWith(BARS))).andRespond(withSuccess("{\"bars\":{},\"next_page_token\":null}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.requireConfirmedClose(SOXL, SESSION))
                .isInstanceOf(IllegalStateException.class).hasMessage("SOXL 종가를 조회하지 못했습니다.");
    }

    @Test
    @DisplayName("requireConfirmedClose: 확정 봉의 종가를 준다")
    void requireConfirmedClose_봉이_있으면_종가() {
        server.expect(requestTo(startsWith(BARS))).andRespond(withSuccess("""
                {"bars":{"SOXL":[{"t":"2026-01-05T05:00:00Z","o":1,"h":1,"l":1,"c":53.81,"v":1,"n":1,"vw":1}]},"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        assertThat(adapter.requireConfirmedClose(SOXL, SESSION)).isEqualByComparingTo("53.81");
    }
}
