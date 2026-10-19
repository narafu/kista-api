package com.kista.tradingcore.stats.adapter.out.coinbase;

import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        // 최신 봉이 먼저 온다: [time, low, high, open, close, volume] — 첫 페이지가 from까지 덮으면 더 부르지 않는다
        server.expect(requestTo("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400"))
                .andRespond(withSuccess("[[1792368000,81634.43,83076.23,82006.85,81735.3,4042.61],"
                        + "[1792281600,80564.57,82183.56,81387.16,82006.85,2937.79]]", MediaType.APPLICATION_JSON));

        var closes = adapter.getUsdDailyClosesSince("BTC", LocalDate.of(2026, 10, 18));
        assertThat(closes).extracting(DatedValue::date).containsExactly(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 19));
        assertThat(closes.getLast().value()).isEqualByComparingTo("81735.3");
        server.verify();
    }

    @Test
    void from까지_모자라면_가장_오래된_봉_전날까지_300일_창으로_거슬러_받고_from_이전은_버린다() {
        LocalDate latest = LocalDate.of(2026, 10, 19);
        LocalDate from = latest.minusDays(400);
        LocalDate firstOldest = latest.minusDays(299); // 첫 페이지 300봉
        server.expect(requestTo("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400"))
                .andRespond(withSuccess(candles(latest, 300), MediaType.APPLICATION_JSON));
        LocalDate end = firstOldest.minusDays(1);
        // 창 시작은 end−299일이 from보다 이르면 from으로 당긴다 (콜론은 %3A 인코딩 — 실 API 수용 확인)
        server.expect(requestTo("https://api.exchange.coinbase.com/products/BTC-USD/candles?granularity=86400&start="
                        + from + "T00%3A00%3A00Z&end=" + end + "T00%3A00%3A00Z"))
                .andRespond(withSuccess(candles(end, 101), MediaType.APPLICATION_JSON));

        var closes = adapter.getUsdDailyClosesSince("BTC", from);

        server.verify();
        assertThat(closes).hasSize(401);
        assertThat(closes.getFirst().date()).isEqualTo(from);
        assertThat(closes.getLast().date()).isEqualTo(latest);
    }

    @Test
    void 상장_이력이_소진돼_빈_페이지가_오면_받은_만큼만_반환한다() {
        LocalDate latest = LocalDate.of(2026, 10, 19);
        server.expect(requestTo("https://api.exchange.coinbase.com/products/USDT-USD/candles?granularity=86400"))
                .andRespond(withSuccess(candles(latest, 10), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.exchange.coinbase.com/products/USDT-USD/candles?granularity=86400&start=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(adapter.getUsdDailyClosesSince("USDT", latest.minusDays(400))).hasSize(10);
        server.verify();
    }

    // newest부터 하루씩 거슬러 n개 일봉 행 JSON (최신 먼저)
    private static String candles(LocalDate newest, int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> "[%d,1,1,1,%d,1]".formatted(newest.minusDays(i).atStartOfDay(ZoneOffset.UTC).toEpochSecond(), i + 1))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
