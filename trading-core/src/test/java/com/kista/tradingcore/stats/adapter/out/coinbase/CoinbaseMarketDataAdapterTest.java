package com.kista.tradingcore.stats.adapter.out.coinbase;

import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoinbaseMarketDataAdapterTest {

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CoinbaseMarketDataAdapter adapter = new CoinbaseMarketDataAdapter(builder.build());

    @Test
    void 현재가는_ticker_price를_읽는다() {
        server.expect(requestTo("https://api.exchange.coinbase.com/products/USDT-USD/ticker"))
                .andRespond(withSuccess("{\"ask\":\"0.99887\",\"price\":\"0.99886\"}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getUsdPrice("USDT")).isEqualByComparingTo("0.99886");
        server.verify();
    }

    @Test
    void 현재가_응답이_비면_실패한다() {
        server.expect(requestTo("https://api.exchange.coinbase.com/products/BTC-USD/ticker"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getUsdPrice("BTC")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 일봉은_시작_epoch초를_UTC_날짜로_종가는_5번째_필드로_읽고_오래된_순으로_정렬한다() {
        // 최신 봉이 먼저 온다: [time, low, high, open, close, volume]
        server.expect(requestTo("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400"))
                .andRespond(withSuccess("[[1792368000,81634.43,83076.23,82006.85,81735.3,4042.61],"
                        + "[1792281600,80564.57,82183.56,81387.16,82006.85,2937.79]]", MediaType.APPLICATION_JSON));

        var closes = adapter.getUsdDailyCloses("BTC");
        assertThat(closes).extracting(DatedValue::date).containsExactly(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19));
        assertThat(closes.getLast().value()).isEqualByComparingTo("81735.3");
        server.verify();
    }
}
