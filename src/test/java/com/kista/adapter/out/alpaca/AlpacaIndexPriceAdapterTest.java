package com.kista.adapter.out.alpaca;

import com.kista.domain.model.backtest.DailyCandle;
import com.kista.domain.model.stats.IndexPrice;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaIndexPriceAdapterTest {

    private final RestTemplate restTemplate = new RestTemplate();
    private final AlpacaProperties properties = new AlpacaProperties(
            "https://paper-api.alpaca.markets", "test-key", "test-secret", "https://data.test");
    private final AlpacaIndexPriceAdapter adapter = new AlpacaIndexPriceAdapter(restTemplate, properties);

    @Test
    void 일별_종가를_미국_거래일로_변환해_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        // t는 UTC — 2024-01-02T05:00:00Z = 뉴욕 2024-01-02 00:00 (미국 거래일 2024-01-02)
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test/v2/stocks/SPY/bars")))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
                // 무료 플랜은 feed=sip 조회 시 end가 최소 15분 이전이어야 하는 제약이 있어
                // 매일 end=오늘로 증분 동기화하는 이 경로는 반드시 feed=iex여야 한다 (회귀 방지)
                .andExpect(queryParam("feed", "iex"))
                .andRespond(withSuccess("""
                        {"bars":[{"t":"2024-01-02T05:00:00Z","c":470.12},
                                 {"t":"2024-01-03T05:00:00Z","c":468.55}],
                         "symbol":"SPY","next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<IndexPrice> result = adapter.fetchDailyCloses(
                "SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).containsExactly(
                new IndexPrice("SPY", LocalDate.of(2024, 1, 2), new BigDecimal("470.12")),
                new IndexPrice("SPY", LocalDate.of(2024, 1, 3), new BigDecimal("468.55")));
    }

    @Test
    void bars가_null이면_빈_목록을_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess("{\"bars\":null,\"symbol\":\"SPY\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.fetchDailyCloses("SPY", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
                .isEmpty();
    }

    @Test
    void 과거_일봉을_OHLC로_매핑해_반환한다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test/v2/stocks/TQQQ/bars")))
                .andExpect(header("APCA-API-KEY-ID", "test-key"))
                // 백테스트용 조회는 수정주가 sip 피드 사용(증분 동기화용 fetchDailyCloses와 대비되는 회귀 방지 포인트)
                .andExpect(queryParam("feed", "sip"))
                .andExpect(queryParam("adjustment", "all"))
                .andRespond(withSuccess("""
                        {"bars":[{"t":"2024-01-02T05:00:00Z","o":100.0,"h":105.5,"l":99.2,"c":104.3,"v":123456},
                                 {"t":"2024-01-03T05:00:00Z","o":104.3,"h":110.0,"l":103.1,"c":108.7,"v":98765}],
                         "symbol":"TQQQ","next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        // to를 넉넉히 과거로 잡아 클램프가 발동하지 않는 경계 확인
        List<DailyCandle> result = adapter.fetchDailyCandles(
                "TQQQ", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5));

        assertThat(result).containsExactly(
                new DailyCandle(LocalDate.of(2024, 1, 2), new BigDecimal("100.0"), new BigDecimal("105.5"),
                        new BigDecimal("99.2"), new BigDecimal("104.3")),
                new DailyCandle(LocalDate.of(2024, 1, 3), new BigDecimal("104.3"), new BigDecimal("110.0"),
                        new BigDecimal("103.1"), new BigDecimal("108.7")));
    }

    @Test
    void 과거_일봉_응답이_비어있으면_예외를_던진다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess("{\"bars\":null,\"symbol\":\"TQQQ\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.fetchDailyCandles(
                "TQQQ", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TQQQ");
    }

    @Test
    void 과거_일봉_응답이_빈_리스트여도_예외를_던진다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test")))
                .andRespond(withSuccess("{\"bars\":[],\"symbol\":\"TQQQ\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.fetchDailyCandles(
                "TQQQ", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void to가_오늘이면_전일_이전으로_클램프되어_요청된다() {
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        LocalDate yesterday = today.minusDays(1);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://data.test/v2/stocks/TQQQ/bars")))
                // end 파라미터가 오늘이 아닌 전일 이전 날짜로 클램프되어 나가는지 검증 (403 방지 핵심 로직)
                .andExpect(queryParam("end", yesterday.toString()))
                .andRespond(withSuccess("""
                        {"bars":[{"t":"2024-01-02T05:00:00Z","o":1.0,"h":2.0,"l":0.5,"c":1.5,"v":1}],
                         "symbol":"TQQQ","next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        adapter.fetchDailyCandles("TQQQ", LocalDate.of(2024, 1, 1), today);

        server.verify();
    }
}
