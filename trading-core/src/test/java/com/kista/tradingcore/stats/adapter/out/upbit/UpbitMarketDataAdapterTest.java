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

    // firstDay(KST 09:00)에서 하루씩 과거로 n개 — utc는 -9h
    private static String candleRows(LocalDate firstDay, int n) {
        return "[" + java.util.stream.IntStream.range(0, n).mapToObj(i -> {
            java.time.LocalDateTime kst = firstDay.minusDays(i).atTime(9, 0);
            return "{\"candle_date_time_kst\":\"%s\",\"candle_date_time_utc\":\"%s\",\"trade_price\":%d}"
                    .formatted(kst, kst.minusHours(9), 100 + i);
        }).collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    @Test
    void pagesWithUnencodedToWhenCountExceeds200() {
        // 1차: 10-17(진행 중)부터 200행 → 가장 오래된 봉의 utc = 그날 00:00:00
        LocalDate first = LocalDate.of(2026, 10, 17);
        LocalDate oldest = first.minusDays(199);
        server.expect(requestTo("https://api.upbit.com/v1/candles/days?market=KRW-BTC&count=200"))
                .andRespond(withSuccess(candleRows(first, 200), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.upbit.com/v1/candles/days?market=KRW-BTC&count=200&to=" + oldest + "T00:00:00"))
                .andRespond(withSuccess(candleRows(oldest.minusDays(1), 200), MediaType.APPLICATION_JSON));

        List<DatedValue> closes = adapter.getCompletedKrwDailyCloses(StrategyTicker.BTC, 250);

        assertThat(closes).hasSize(250);
        assertThat(closes.getLast().date()).isEqualTo(LocalDate.of(2026, 10, 16));
        assertThat(closes).extracting(DatedValue::date).doesNotHaveDuplicates().isSorted();
        server.verify();
    }
}
