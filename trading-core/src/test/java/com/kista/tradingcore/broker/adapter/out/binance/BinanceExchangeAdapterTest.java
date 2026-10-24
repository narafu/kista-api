package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.CryptoTradingRules;
import com.kista.shared.kernel.CryptoTradingRules.FeeSide;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult;
import com.kista.tradingcore.broker.domain.model.CryptoOrderResult.State;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceExchangeAdapterTest {

    private static final String BASE = "https://api.binance.com";
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(null, "ak", "sk", null, null, Broker.BINANCE);
    // 2026-10-23 10:30 KST — 10-23 00:00Z에 시작한 봉은 진행 중
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T01:30:00Z"), ZoneOffset.UTC);

    private MockRestServiceServer server;
    private BinanceExchangeAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        BinanceHttpClient http = new BinanceHttpClient(builder.build(), JsonMapper.builder().build(), BASE, CLOCK);
        adapter = new BinanceExchangeAdapter(http, new BinanceSymbolRegistry(), CLOCK);
    }

    @AfterEach
    void verifyServer() {
        server.verify(); // 기대한 요청이 모두 나갔는지 — 모든 테스트 공통
    }

    @Test
    void balanceMapsUsdtAndSupportedAssetsWithoutAvgPrice() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/account?omitZeroBalances=true&")))
                .andExpect(header("X-MBX-APIKEY", "ak"))
                .andRespond(withSuccess("""
                        {"balances":[{"asset":"USDT","free":"100.12","locked":"5.00"},
                        {"asset":"BTC","free":"0.00150000","locked":"0"},{"asset":"BNB","free":"1","locked":"0"}]}
                        """, APPLICATION_JSON));

        CryptoBalance b = adapter.getBalance(ACCOUNT);

        assertThat(b.quoteAvailable()).isEqualByComparingTo("100.12");
        assertThat(b.quoteLocked()).isEqualByComparingTo("5.00");
        assertThat(b.holdings()).containsOnlyKeys(StrategyTicker.BTC);
        assertThat(b.holdings().get(StrategyTicker.BTC).available()).isEqualByComparingTo("0.0015");
        assertThat(b.holdings().get(StrategyTicker.BTC).avgBuyPrice()).isNull();
    }

    @Test
    void currentPricesPerSymbol() {
        server.expect(requestTo(BASE + "/api/v3/ticker/price?symbol=BTCUSDT")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"symbol\":\"BTCUSDT\",\"price\":\"65000.01\"}", APPLICATION_JSON));

        Map<StrategyTicker, BigDecimal> prices = adapter.getCurrentPrices(List.of(StrategyTicker.BTC));

        assertThat(prices).containsOnlyKeys(StrategyTicker.BTC);
        assertThat(prices.get(StrategyTicker.BTC)).isEqualByComparingTo("65000.01");
        assertThat(adapter.getCurrentPrices(List.of())).isEmpty();
    }

    @Test
    void completedCandlesDropInProgressAndUseKstStartDate() {
        // 10-21 00:00Z, 10-22 00:00Z, 10-23 00:00Z(진행 중)
        server.expect(requestTo(BASE + "/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=3"))
                .andRespond(withSuccess("""
                        [[1792540800000,"1","2","0.5","101.5",""],[1792627200000,"1","2","0.5","102.5",""],[1792713600000,"1","2","0.5","103.5",""]]
                        """, APPLICATION_JSON));

        List<CryptoCandle> candles = adapter.getCompletedDailyCandles(StrategyTicker.BTC, 2);

        assertThat(candles).extracting(CryptoCandle::kstDate).containsExactly(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22));
        assertThat(candles.get(1).close()).isEqualByComparingTo("102.5");
        assertThat(candles.getLast().kstDate())
                .isEqualTo(CryptoCandle.latestCompletedKstDate(LocalDateTime.of(2026, 10, 23, 10, 30)))
                .isEqualTo(LocalDate.of(2026, 10, 22));
    }

    @Test
    void candleClosingExactlyAtNowIsIncluded() {
        // now = 10-23 00:00Z 정각 — 10-22 봉은 openTime + 1일 == now 라 완료(제외 조건은 openTime + 1일 > now)
        Clock boundary = Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer boundaryServer = MockRestServiceServer.bindTo(builder).build();
        BinanceExchangeAdapter boundaryAdapter = new BinanceExchangeAdapter(
                new BinanceHttpClient(builder.build(), JsonMapper.builder().build(), BASE, boundary), new BinanceSymbolRegistry(), boundary);
        boundaryServer.expect(requestTo(BASE + "/api/v3/klines?symbol=BTCUSDT&interval=1d&limit=2"))
                .andRespond(withSuccess("""
                        [[1792540800000,"1","2","0.5","101.5",""],[1792627200000,"1","2","0.5","102.5",""]]
                        """, APPLICATION_JSON));

        List<CryptoCandle> candles = boundaryAdapter.getCompletedDailyCandles(StrategyTicker.BTC, 1);

        assertThat(candles).extracting(CryptoCandle::kstDate).containsExactly(LocalDate.of(2026, 10, 22));
        boundaryServer.verify();
    }

    @Test
    void tradingRulesFromExchangeInfo() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andRespond(withSuccess(exchangeInfo("TRADING"), APPLICATION_JSON));

        CryptoTradingRules r = adapter.getTradingRules(StrategyTicker.BTC);

        assertThat(r.feeSide()).isEqualTo(FeeSide.RECEIVED_ASSET);
        assertThat(r.feeRate()).isEqualByComparingTo("0.001");
        assertThat(r.minNotional()).isEqualByComparingTo("5");
        assertThat(r.quantityScale()).isEqualTo(5);
    }

    @Test
    void emptySymbolsInExchangeInfoThrowsNoSuchElement() {
        // 현재 동작 고정 — getFirst() 미검증이라 NoSuchElementException (BinanceApiException 아님)
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"symbols\":[]}", APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getTradingRules(StrategyTicker.BTC)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void nonTradingSymbolIsUnknownFailure() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andRespond(withSuccess(exchangeInfo("CANCEL_ONLY"), APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getTradingRules(StrategyTicker.BTC))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void candleCountOutOfRangeRejected() {
        server.expect(never(), requestTo(org.hamcrest.Matchers.any(String.class))).andRespond(withSuccess());
        assertThatThrownBy(() -> adapter.getCompletedDailyCandles(StrategyTicker.BTC, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.getCompletedDailyCandles(StrategyTicker.BTC, 1000)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    private static final String UUID_ID = "0192f0aa-1111-7222-8333-444455556666";
    private static final String WIRE_ID = "0192f0aa111172228333444455556666";

    private static String orderJson(String status, String executed, String quote, String fills) {
        return """
                {"symbol":"BTCUSDT","orderId":28,"clientOrderId":"%s","status":"%s","executedQty":"%s","cummulativeQuoteQty":"%s","fills":%s}
                """.formatted(WIRE_ID, status, executed, quote, fills);
    }

    private static final String ONE_BTC_FILL = "[{\"price\":\"65000.00\",\"qty\":\"0.00016000\",\"commission\":\"0.00000016\",\"commissionAsset\":\"BTC\"}]";

    private void expectOrderGet(String param, String value, String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(method(HttpMethod.GET)).andExpect(queryParam("symbol", "BTCUSDT")).andExpect(queryParam(param, value))
                .andRespond(withSuccess(body, APPLICATION_JSON));
    }

    private void expectMyTrades(String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/myTrades?")))
                .andExpect(queryParam("symbol", "BTCUSDT")).andExpect(queryParam("orderId", "28"))
                .andExpect(header("X-MBX-APIKEY", "ak")).andExpect(queryParam("signature", org.hamcrest.Matchers.hasLength(64)))
                .andRespond(withSuccess(body, APPLICATION_JSON));
    }

    private static final String TRADES_2 = """
            [{"price":"65000.00","qty":"0.00010000","commission":"0.00000010","commissionAsset":"BTC"},
            {"price":"65000.00","qty":"0.00006000","commission":"0.00000005","commissionAsset":"BTC"}]
            """;

    private void expectLookupError(String param, String value, int status, String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(queryParam(param, value))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(APPLICATION_JSON).body(body));
    }

    @Test
    void buyUsesQuoteOrderQtyPlain() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(method(HttpMethod.POST)).andExpect(header("X-MBX-APIKEY", "ak"))
                .andExpect(queryParam("symbol", "BTCUSDT")).andExpect(queryParam("side", "BUY")).andExpect(queryParam("type", "MARKET"))
                .andExpect(queryParam("quoteOrderQty", "10.5")).andExpect(queryParam("newClientOrderId", WIRE_ID))
                .andExpect(queryParam("newOrderRespType", "FULL")).andExpect(queryParam("recvWindow", "5000"))
                .andExpect(queryParam("timestamp", String.valueOf(CLOCK.millis())))
                .andRespond(withSuccess(orderJson("FILLED", "0.00016000", "10.40000000", ONE_BTC_FILL), APPLICATION_JSON));

        CryptoOrderResult r = adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("10.50"), UUID_ID);

        assertThat(r.exchangeOrderId()).isEqualTo("28");
        assertThat(r.clientOrderId()).isEqualTo(UUID_ID);
        assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00015984");
        server.verify();
    }

    @Test
    void buyNonPositiveAmountRejectedBeforeSend() {
        assertThatThrownBy(() -> adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, BigDecimal.ZERO, UUID_ID))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify(); // 요청 0건
    }

    @Test
    void clientOrderIdWireFormIsSameOnPostAndGet() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(method(HttpMethod.POST)).andExpect(queryParam("newClientOrderId", WIRE_ID))
                .andRespond(withSuccess(orderJson("NEW", "0.00000000", "0.00000000", "[]"), APPLICATION_JSON));
        expectOrderGet("origClientOrderId", WIRE_ID, orderJson("NEW", "0.00000000", "0.00000000", "[]"));

        adapter.buyMarketByAmount(ACCOUNT, StrategyTicker.BTC, new BigDecimal("10.50"), UUID_ID);
        CryptoOrderResult r = adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID).orElseThrow();

        assertThat(r.clientOrderId()).isEqualTo(UUID_ID);
        server.verify();
    }

    @Test
    void filledBuyNetsBaseAssetCommissionFromMyTrades() {
        expectOrderGet("orderId", "28", orderJson("FILLED", "0.00016000", "10.40000000", "[]"));
        expectMyTrades(TRADES_2);

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();

        assertThat(r.state()).isEqualTo(State.DONE);
        assertThat(r.executedVolume()).isEqualByComparingTo("0.00016");
        assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00015985");
        assertThat(r.avgPrice()).isEqualByComparingTo("65000");
        assertThat(r.paidFee()).isEqualByComparingTo("0.00000015");
        assertThat(r.feeAsset()).isEqualTo("BTC");
        server.verify();
    }

    @Test
    void bnbCommissionDoesNotReduceNet() {
        expectOrderGet("orderId", "28", orderJson("FILLED", "0.00016000", "10.40000000", "[]"));
        expectMyTrades("[{\"price\":\"65000.00\",\"qty\":\"0.00016000\",\"commission\":\"0.00001\",\"commissionAsset\":\"BNB\"}]");

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();

        assertThat(r.netExecutedVolume()).isEqualByComparingTo(r.executedVolume());
        assertThat(r.feeAsset()).isEqualTo("BNB");
    }

    @Test
    void sellPostResponseFillsNetEqualsExecuted() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).andRespond(withSuccess(exchangeInfo("TRADING"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(method(HttpMethod.POST)).andExpect(queryParam("side", "SELL"))
                .andRespond(withSuccess(orderJson("FILLED", "0.00016000", "10.40000000",
                        "[{\"price\":\"65000.00\",\"qty\":\"0.00016000\",\"commission\":\"0.01040000\",\"commissionAsset\":\"USDT\"}]"), APPLICATION_JSON));

        CryptoOrderResult r = adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.BTC, new BigDecimal("0.00016"), UUID_ID);

        // 견적 자산(USDT) 수수료는 수량에서 차감되지 않는다
        assertThat(r.state()).isEqualTo(State.DONE);
        assertThat(r.netExecutedVolume()).isEqualByComparingTo(r.executedVolume());
        assertThat(r.paidFee()).isEqualByComparingTo("0.0104");
        assertThat(r.feeAsset()).isEqualTo("USDT");
    }

    @Test
    void sellNetEqualsExecuted() {
        expectOrderGet("orderId", "28", orderJson("FILLED", "0.00016000", "10.40000000", "[]"));
        expectMyTrades("[{\"price\":\"65000.00\",\"qty\":\"0.00016000\",\"commission\":\"0.01040000\",\"commissionAsset\":\"USDT\"}]");

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();

        assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00016");
        assertThat(r.feeAsset()).isEqualTo("USDT");
    }

    @Test
    void expiredWithFillCountsAsClosedFill() {
        expectOrderGet("orderId", "28", orderJson("EXPIRED", "0.00016000", "10.40000000", "[]"));
        expectMyTrades(TRADES_2);

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();

        assertThat(r.state()).isEqualTo(State.CANCEL);
        assertThat(r.executedVolume()).isEqualByComparingTo("0.00016");
        assertThat(r.netExecutedVolume()).isEqualByComparingTo("0.00015985");
        server.verify(); // myTrades 호출됨
    }

    @Test
    void openOrderSkipsMyTrades() {
        expectOrderGet("orderId", "28", orderJson("NEW", "0.00000000", "0.00000000", "[]"));

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();

        assertThat(r.state()).isEqualTo(State.WAIT);
        assertThat(r.avgPrice()).isNull();
        server.verify(); // order 1건만 기대 — myTrades 호출 시 이미 실패
    }

    @Test
    void myTradesShortOfExecutedThrows() {
        expectOrderGet("orderId", "28", orderJson("FILLED", "0.00016000", "10.40000000", "[]"));
        expectMyTrades("[{\"price\":\"65000.00\",\"qty\":\"0.00010000\",\"commission\":\"0.00000010\",\"commissionAsset\":\"BTC\"}]");

        assertThatThrownBy(() -> adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28"))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void sellFloorsToLotSizeStep() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).andRespond(withSuccess(exchangeInfo("TRADING"), APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andExpect(method(HttpMethod.POST)).andExpect(queryParam("side", "SELL")).andExpect(queryParam("type", "MARKET"))
                .andExpect(queryParam("quantity", "0.00015")).andExpect(queryParam("newClientOrderId", WIRE_ID))
                .andExpect(queryParam("newOrderRespType", "FULL"))
                .andRespond(withSuccess(orderJson("NEW", "0", "0", "[]"), APPLICATION_JSON));

        adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.BTC, new BigDecimal("0.000159999"), UUID_ID);

        server.verify();
    }

    @Test
    void sellBelowStepRejectedWithoutOrderRequest() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).andRespond(withSuccess(exchangeInfo("TRADING"), APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.sellMarketByVolume(ACCOUNT, StrategyTicker.BTC, new BigDecimal("0.000009"), UUID_ID))
                .isInstanceOf(IllegalArgumentException.class);
        server.verify(); // exchangeInfo 1건만 — 주문 요청 0건
    }

    @Test
    void lookup_minus2013_isEmpty() {
        String body = "{\"code\":-2013,\"msg\":\"Order does not exist.\"}";
        expectLookupError("orderId", "28", 400, body);
        expectLookupError("origClientOrderId", WIRE_ID, 400, body);

        assertThat(adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28")).isEmpty();
        assertThat(adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID)).isEmpty();
    }

    @Test
    void lookup_minus2026() {
        String body = "{\"code\":-2026,\"msg\":\"Order was canceled or expired with no executed qty over 90 days ago and has been archived.\"}";
        expectLookupError("orderId", "28", 400, body);
        expectLookupError("origClientOrderId", WIRE_ID, 400, body);

        CryptoOrderResult r = adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28").orElseThrow();
        assertThat(r.state()).isEqualTo(State.CANCEL);
        assertThat(r.executedVolume()).isEqualByComparingTo("0");
        assertThat(adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID)).isEmpty();
    }

    @Test
    void lookup_5xx_throwsNotEmpty() {
        expectLookupError("origClientOrderId", WIRE_ID, 502, "<html>bad gateway</html>");

        assertThatThrownBy(() -> adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void lookup_minus1007_throwsUnknown() {
        expectLookupError("origClientOrderId", WIRE_ID, 400, "{\"code\":-1007,\"msg\":\"Timeout waiting for response from backend server.\"}");

        assertThatThrownBy(() -> adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID))
                .isInstanceOf(BinanceApiException.class)
                .satisfies(e -> assertThat(BrokerApiException.isRejection(e)).isFalse());
    }

    @Test
    void lookup_ioError_throws() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v3/order?")))
                .andRespond(withException(new IOException("reset")));

        assertThatThrownBy(() -> adapter.findOrderByClientOrderId(ACCOUNT, StrategyTicker.BTC, UUID_ID))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void unknownStatusIsUnknownFailure() {
        expectOrderGet("orderId", "28", orderJson("FOO", "0", "0", "[]"));

        assertThatThrownBy(() -> adapter.findOrderByExchangeOrderId(ACCOUNT, StrategyTicker.BTC, "28"))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void tradingRulesFallBackToMinNotional() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).andRespond(withSuccess(exchangeInfoWith(
                "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.00001000\"},{\"filterType\":\"MIN_NOTIONAL\",\"minNotional\":\"10.00000000\"}"),
                APPLICATION_JSON));

        assertThat(adapter.getTradingRules(StrategyTicker.BTC).minNotional()).isEqualByComparingTo("10");
    }

    @Test
    void tradingRulesWithoutNotionalFilterThrows() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT")).andRespond(withSuccess(exchangeInfoWith(
                "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.00001000\"}"), APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getTradingRules(StrategyTicker.BTC)).isInstanceOf(IllegalStateException.class);
    }

    private static String exchangeInfoWith(String filters) {
        return "{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"filters\":[" + filters + "]}]}";
    }

    private static String exchangeInfo(String status) {
        return """
                {"symbols":[{"symbol":"BTCUSDT","status":"%s","filters":[
                {"filterType":"PRICE_FILTER","tickSize":"0.01"},
                {"filterType":"LOT_SIZE","minQty":"0.00001","stepSize":"0.00001000"},
                {"filterType":"NOTIONAL","minNotional":"5.00000000"}]}]}
                """.formatted(status);
    }
}
