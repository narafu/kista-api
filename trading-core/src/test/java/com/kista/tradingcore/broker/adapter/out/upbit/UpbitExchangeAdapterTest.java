package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.upbit.UpbitApiException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitExchangeAdapterTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(null, "ak", "sk-0123456789012345678901234567890123", null, null, Broker.UPBIT);
    // 2026-10-17 10:30 KST — 10-17 09:00에 시작한 봉은 진행 중
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:30:00Z"), ZoneId.of("Asia/Seoul"));

    private MockRestServiceServer server;
    private UpbitExchangeAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        UpbitHttpClient http = new UpbitHttpClient(builder.build(), JsonMapper.builder().build(), () -> "n");
        adapter = new UpbitExchangeAdapter(http, new UpbitMarketRegistry(), CLOCK);
    }

    @Test
    void supportsUpbit() {
        assertThat(adapter.supports()).isEqualTo(Broker.UPBIT);
    }

    @Test
    void balanceParsesStringDecimalsAndSkipsUnsupportedCoins() {
        server.expect(requestTo(BASE_URL + "/v1/accounts"))
                .andExpect(header("Authorization", Matchers.startsWith("Bearer ")))
                .andRespond(withSuccess("""
                        [{"currency":"KRW","balance":"1000000.0","locked":"5000.0","avg_buy_price":"0","avg_buy_price_modified":false,"unit_currency":"KRW"},
                         {"currency":"BTC","balance":"0.00123456","locked":"0.0","avg_buy_price":"150000000","avg_buy_price_modified":false,"unit_currency":"KRW"},
                         {"currency":"XRP","balance":"10","locked":"0","avg_buy_price":"800","avg_buy_price_modified":false,"unit_currency":"KRW"}]
                        """, MediaType.APPLICATION_JSON));

        CryptoBalance balance = adapter.getBalance(ACCOUNT);

        assertThat(balance.krwAvailable()).isEqualByComparingTo("1000000");
        assertThat(balance.krwLocked()).isEqualByComparingTo("5000");
        assertThat(balance.holdings()).containsOnlyKeys(StrategyTicker.BTC);
        assertThat(balance.holdings().get(StrategyTicker.BTC).available()).isEqualByComparingTo("0.00123456");
        assertThat(balance.holdings().get(StrategyTicker.BTC).avgBuyPrice()).isEqualByComparingTo("150000000");
    }

    @Test
    void currentPricesUsePublicTicker() {
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-BTC,KRW-ETH"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                        [{"market":"KRW-BTC","trade_price":151000000.0},{"market":"KRW-ETH","trade_price":5200000.0}]
                        """, MediaType.APPLICATION_JSON));

        var prices = adapter.getCurrentPrices(List.of(StrategyTicker.BTC, StrategyTicker.ETH));

        assertThat(prices).containsOnlyKeys(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(prices.get(StrategyTicker.BTC)).isEqualByComparingTo("151000000"); // scale은 JSON 표기에 따름 — 값만 비교
    }

    @Test
    void completedCandlesDropInProgressAndSortAscending() {
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=3"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-17T09:00:00","trade_price":152000000},
                         {"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":151000000},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":150000000}]
                        """, MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 2);

        assertThat(candles).extracting(CryptoCandle::kstDate)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(candles.getLast().close()).isEqualByComparingTo("151000000");
    }

    @Test
    void USDT_원화_현재가를_KRW_USDT_마켓으로_조회한다() {
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-USDT"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("[{\"market\":\"KRW-USDT\",\"trade_price\":1452.0}]", MediaType.APPLICATION_JSON));

        assertThat(adapter.getUsdtKrwPrice()).isEqualByComparingTo("1452");
    }

    @Test
    void USDT_원화_현재가_응답이_비면_예외를_던진다() {
        server.expect(requestTo(BASE_URL + "/v1/ticker?markets=KRW-USDT"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getUsdtKrwPrice())
                .isInstanceOf(UpbitApiException.class);
    }

    @Test
    void USDT_원화_완성_일봉은_진행중_봉을_제외한다() {
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-USDT&count=3"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-17T09:00:00","trade_price":1455},
                         {"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":1450},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":1448}]
                        """, MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedUsdtKrwDailyCandles(2);

        assertThat(candles).extracting(CryptoCandle::kstDate)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(candles.getLast().close()).isEqualByComparingTo("1450");
    }

    // startKst = firstDay(KST 09:00)에서 하루씩 과거로 n개 — utc는 -9h
    private static String candleRows(LocalDate firstDay, int n) {
        return "[" + IntStream.range(0, n).mapToObj(i -> {
            LocalDateTime kst = firstDay.minusDays(i).atTime(9, 0);
            return "{\"candle_date_time_kst\":\"%s\",\"candle_date_time_utc\":\"%s\",\"trade_price\":%d}"
                    .formatted(kst, kst.minusHours(9), 100 + i);
        }).collect(Collectors.joining(",")) + "]";
    }

    @Test
    void pagesWithToWhenCountExceeds200() {
        // 1차: 10-17(진행 중)부터 200행 → 가장 오래된 봉의 utc = 그날 00:00:00 (KST 09:00)
        LocalDate first = LocalDate.of(2026, 10, 17);
        LocalDate oldest = first.minusDays(199);
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200"))
                .andRespond(withSuccess(candleRows(first, 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200&to=" + oldest + "T00:00:00"))
                .andRespond(withSuccess(candleRows(oldest.minusDays(1), 200), MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 250);

        assertThat(candles).hasSize(250);
        assertThat(candles.getLast().kstDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(candles).extracting(CryptoCandle::kstDate).doesNotHaveDuplicates().isSorted();
        server.verify();
    }

    @Test
    void count200NeedsSecondPageBecauseFirstPageYields199Completed() {
        // 1차: count=min(201,200)=200 요청, 진행 중 봉 1개 버려 199개 → 부족해 2차 요청
        LocalDate first = LocalDate.of(2026, 10, 17);
        LocalDate oldest = first.minusDays(199);
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200"))
                .andRespond(withSuccess(candleRows(first, 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200&to=" + oldest + "T00:00:00"))
                .andRespond(withSuccess(candleRows(oldest.minusDays(1), 200), MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 200);

        assertThat(candles).hasSize(200);
        assertThat(candles.getLast().kstDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(candles.getFirst().kstDate()).isEqualTo(LocalDate.of(2026, 10, 16).minusDays(199));
        assertThat(candles).extracting(CryptoCandle::kstDate).doesNotHaveDuplicates().isSorted();
        server.verify();
    }

    @Test
    void count400UsesThreePages() {
        LocalDate first = LocalDate.of(2026, 10, 17);
        LocalDate oldest1 = first.minusDays(199);  // 1차 응답 중 가장 오래된 봉
        LocalDate oldest2 = first.minusDays(399);  // 2차 응답 중 가장 오래된 봉
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200"))
                .andRespond(withSuccess(candleRows(first, 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200&to=" + oldest1 + "T00:00:00"))
                .andRespond(withSuccess(candleRows(oldest1.minusDays(1), 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200&to=" + oldest2 + "T00:00:00"))
                .andRespond(withSuccess(candleRows(oldest2.minusDays(1), 200), MediaType.APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 400);

        assertThat(candles).hasSize(400);
        assertThat(candles.getLast().kstDate()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(candles).extracting(CryptoCandle::kstDate).doesNotHaveDuplicates().isSorted();
        server.verify();
    }

    @Test
    void stopsWhenHistoryRunsOut() {
        server.expect(requestTo(Matchers.startsWith(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=200")))
                .andRespond(withSuccess(candleRows(LocalDate.of(2026, 10, 17), 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo(Matchers.containsString("&to=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(adapter.getCompletedDailyCandles(StrategyTicker.BTC, 250)).hasSize(199);
        server.verify();
    }

    @Test
    void rejectsCountOver400() {
        assertThatThrownBy(() -> adapter.getCompletedDailyCandles(StrategyTicker.BTC, 401))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marketBuyPostsPriceOrderWithIdentifier() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"market":"KRW-BTC","side":"bid","ord_type":"price","price":"10000","identifier":"id-1"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"uuid":"u-1","identifier":"id-1","state":"wait","executed_volume":"0.0","paid_fee":"0.0"}
                        """));

        CryptoOrderResult r = adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("10000"), "id-1");

        assertThat(r.uuid()).isEqualTo("u-1");
        assertThat(r.state()).isEqualTo(CryptoOrderResult.State.WAIT);
        assertThat(r.avgPrice()).isNull();
    }

    @Test
    void marketSellPostsVolumeOrder() {
        server.expect(requestTo(BASE_URL + "/v1/orders"))
                .andExpect(content().json("""
                        {"market":"KRW-ETH","side":"ask","ord_type":"market","volume":"0.00123456","identifier":"id-2"}
                        """, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("""
                        {"uuid":"u-2","identifier":"id-2","state":"wait","executed_volume":"0","paid_fee":"0"}
                        """));

        assertThat(adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.ETH, new BigDecimal("0.00123456"), "id-2").uuid())
                .isEqualTo("u-2");
    }

    @Test
    void findOrderComputesAveragePriceFromTrades() {
        server.expect(requestTo(BASE_URL + "/v1/order?identifier=id-1"))
                .andRespond(withSuccess("""
                        {"uuid":"u-1","identifier":"id-1","state":"done","executed_volume":"0.0002","paid_fee":"5",
                         "trades":[{"price":"150000000","volume":"0.0001","funds":"15000"},
                                   {"price":"151000000","volume":"0.0001","funds":"15100"}]}
                        """, MediaType.APPLICATION_JSON));

        CryptoOrderResult r = adapter.findOrderByIdentifier(ACCOUNT, "id-1").orElseThrow();

        assertThat(r.state()).isEqualTo(CryptoOrderResult.State.DONE);
        assertThat(r.executedVolume()).isEqualByComparingTo("0.0002");
        assertThat(r.avgPrice()).isEqualByComparingTo("150500000");
    }

    @Test
    void findOrderNotFoundIsEmpty() {
        server.expect(requestTo(BASE_URL + "/v1/order?uuid=missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"name\":\"order_not_found\",\"message\":\"주문을 찾지 못했습니다.\"}}"));

        assertThat(adapter.findOrderByUuid(ACCOUNT, "missing")).isEmpty();
    }

    @Test
    void candlesBeforeNineKstKeepTodayStartedCandleOut() {
        // 2026-10-18 08:30 KST — 10-17 09:00 봉은 아직 진행 중, 마지막 완성 봉은 10-16
        Clock early = Clock.fixed(Instant.parse("2026-10-17T23:30:00Z"), ZoneId.of("Asia/Seoul"));
        RestClient.Builder b = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer s = MockRestServiceServer.bindTo(b).build();
        UpbitExchangeAdapter a = new UpbitExchangeAdapter(new UpbitHttpClient(b.build(), JsonMapper.builder().build(), () -> "n"), new UpbitMarketRegistry(), early);
        s.expect(requestTo(BASE_URL + "/v1/candles/days?market=KRW-BTC&count=3"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-17T09:00:00","trade_price":152000000},
                         {"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":151000000},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":150000000}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(a.getCompletedDailyCandles(StrategyTicker.BTC, 2)).extracting(CryptoCandle::kstDate)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
    }

    @Test
    void authenticatedGetSignsTheQueryActuallySent() {
        UpbitHttpClient http = new UpbitHttpClient(RestClient.builder().baseUrl(BASE_URL).build(), JsonMapper.builder().build(), () -> "n");
        String expected = "Bearer " + http.token("ak", "sk-0123456789012345678901234567890123", "identifier=id-1");
        server.expect(requestTo(BASE_URL + "/v1/order?identifier=id-1"))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess("{\"uuid\":\"u\",\"identifier\":\"id-1\",\"state\":\"wait\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.findOrderByIdentifier(ACCOUNT, "id-1")).isPresent();
    }

    private void expectOrder(String json, String state) {
        server.expect(requestTo(BASE_URL + "/v1/orders")).andExpect(content().json(json, org.springframework.test.json.JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"uuid\":\"u\",\"identifier\":\"i\"" + (state == null ? "" : ",\"state\":\"" + state + "\"") + "}"));
    }

    @Test
    void buyAmountStripsTrailingZeros() {
        expectOrder("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"price\",\"price\":\"10000\",\"identifier\":\"i\"}", "wait");
        adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("10000.00000000"), "i");
    }

    @Test
    void sellVolumeRoundsDownToEightDecimals() {
        expectOrder("{\"market\":\"KRW-BTC\",\"side\":\"ask\",\"ord_type\":\"market\",\"volume\":\"0.12345678\",\"identifier\":\"i\"}", "wait");
        adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.BTC, new BigDecimal("0.123456789999"), "i");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        assertThatThrownBy(() -> adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, BigDecimal.ZERO, "i"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.BTC, new BigDecimal("0.000000001"), "i"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownOrMissingStateThrowsUpbitApiException() {
        expectOrder("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"price\",\"price\":\"5000\",\"identifier\":\"i\"}", "weird");
        expectOrder("{\"market\":\"KRW-BTC\",\"side\":\"bid\",\"ord_type\":\"price\",\"price\":\"5000\",\"identifier\":\"i\"}", null);
        assertThatThrownBy(() -> adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("5000"), "i"))
                .isInstanceOf(UpbitApiException.class);
        assertThatThrownBy(() -> adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("5000"), "i"))
                .isInstanceOf(UpbitApiException.class);
    }

    @Test
    void balanceSkipsNonKrwUnitCurrencyRows() {
        server.expect(requestTo(BASE_URL + "/v1/accounts")).andRespond(withSuccess("""
                [{"currency":"KRW","balance":"1","locked":"0","avg_buy_price":"0","unit_currency":"KRW"},
                 {"currency":"BTC","balance":"1","locked":"0","avg_buy_price":"100","unit_currency":"USDT"}]
                """, MediaType.APPLICATION_JSON));

        assertThat(adapter.getBalance(ACCOUNT).holdings()).isEmpty();
    }
}
