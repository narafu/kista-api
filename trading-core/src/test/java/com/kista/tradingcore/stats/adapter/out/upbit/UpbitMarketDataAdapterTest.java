package com.kista.tradingcore.stats.adapter.out.upbit;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitMarketDataAdapterTest {

    // 2026-10-17 10:30 KST — 10-17 09:00에 시작한 봉은 진행 중
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:30:00Z"), ZoneId.of("Asia/Seoul"));

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final UpbitMarketDataAdapter adapter = new UpbitMarketDataAdapter(builder.build(), CLOCK);

    @Test
    void usdtKrwPriceUsesKrwUsdtMarket() {
        server.expect(requestTo("https://api.upbit.com/v1/ticker?markets=KRW-USDT"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("[{\"market\":\"KRW-USDT\",\"trade_price\":1452}]", MediaType.APPLICATION_JSON));

        assertThat(adapter.getUsdtKrwPrice()).isEqualByComparingTo("1452");
        server.verify();
    }

    @Test
    void emptyUsdtResponseThrows() {
        server.expect(requestTo("https://api.upbit.com/v1/ticker?markets=KRW-USDT"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(adapter::getUsdtKrwPrice).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void completedUsdtClosesDropInProgressCandle() {
        server.expect(requestTo("https://api.upbit.com/v1/candles/days?market=KRW-USDT&count=3"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-17T09:00:00","trade_price":1455},
                         {"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":1450},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":1448}]
                        """, MediaType.APPLICATION_JSON));

        List<DatedValue> closes = adapter.getCompletedUsdtKrwDailyCloses(2);

        assertThat(closes).extracting(DatedValue::date)
                .containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(closes.getLast().value()).isEqualByComparingTo("1450");
    }

    @Test
    void krwPricesMapTickersToKrwMarkets() {
        server.expect(requestTo("https://api.upbit.com/v1/ticker?markets=KRW-BTC,KRW-ETH"))
                .andRespond(withSuccess("""
                        [{"market":"KRW-BTC","trade_price":151000000},{"market":"KRW-ETH","trade_price":5200000}]
                        """, MediaType.APPLICATION_JSON));

        Map<StrategyTicker, BigDecimal> prices = adapter.getKrwPrices(
                new java.util.LinkedHashSet<>(List.of(StrategyTicker.BTC, StrategyTicker.ETH)));

        assertThat(prices.get(StrategyTicker.BTC)).isEqualByComparingTo("151000000");
        assertThat(prices.get(StrategyTicker.ETH)).isEqualByComparingTo("5200000");
    }

    @Test
    void completedKrwClosesUseCoinMarket() {
        server.expect(requestTo("https://api.upbit.com/v1/candles/days?market=KRW-BTC&count=2"))
                .andRespond(withSuccess("""
                        [{"candle_date_time_kst":"2026-10-16T09:00:00","trade_price":151000000},
                         {"candle_date_time_kst":"2026-10-15T09:00:00","trade_price":150000000}]
                        """, MediaType.APPLICATION_JSON));

        assertThat(adapter.getCompletedKrwDailyCloses(StrategyTicker.BTC, 1)).extracting(DatedValue::date)
                .containsExactly(LocalDate.of(2026, 10, 16));
    }
}
