package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.CryptoTradingRules;
import com.kista.shared.kernel.CryptoTradingRules.FeeSide;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CryptoBalance;
import com.kista.tradingcore.broker.domain.model.CryptoCandle;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
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

        Map<StrategyTicker, java.math.BigDecimal> prices = adapter.getCurrentPrices(List.of(StrategyTicker.BTC));

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
                .isEqualTo(CryptoCandle.latestCompletedKstDate(java.time.LocalDateTime.of(2026, 10, 23, 10, 30)))
                .isEqualTo(LocalDate.of(2026, 10, 22));
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

    private static String exchangeInfo(String status) {
        return """
                {"symbols":[{"symbol":"BTCUSDT","status":"%s","filters":[
                {"filterType":"PRICE_FILTER","tickSize":"0.01"},
                {"filterType":"LOT_SIZE","minQty":"0.00001","stepSize":"0.00001000"},
                {"filterType":"NOTIONAL","minNotional":"5.00000000"}]}]}
                """.formatted(status);
    }
}
