package com.kista.tradingcore.stats.adapter.out.coinmarketcap;

import com.kista.tradingcore.stats.domain.model.DatedValue;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoinMarketCapAdapterTest {

    private static final String OFFICIAL = "https://pro-api.coinmarketcap.com";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final CoinMarketCapAdapter adapter = new CoinMarketCapAdapter(builder.build(), new CoinMarketCapProperties("test-key"));

    @Test
    void 도미넌스_현재값은_latest의_btc_dominance() {
        server.expect(requestTo(OFFICIAL + "/v1/global-metrics/quotes/latest"))
                .andExpect(header("X-CMC_PRO_API_KEY", "test-key"))
                .andRespond(withSuccess("{\"status\":{\"error_code\":0},\"data\":{\"btc_dominance\":60.147128048832,\"eth_dominance\":12.3}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.btcDominance()).isEqualByComparingTo("60.147128048832");
        server.verify();
    }

    @Test
    void 도미넌스_이력은_UTC_날짜별() {
        server.expect(requestTo(OFFICIAL + "/v1/global-metrics/quotes/historical?interval=daily&count=365"))
                .andExpect(header("X-CMC_PRO_API_KEY", "test-key"))
                .andRespond(withSuccess("""
                        {"status":{"error_code":0},"data":{"quotes":[
                          {"timestamp":"2026-10-15T00:00:00.000Z","quote":{"USD":{}},"btc_dominance":59.8558},
                          {"timestamp":"2026-10-16T00:00:00.000Z","quote":{"USD":{}},"btc_dominance":60.1}]}}
                        """, MediaType.APPLICATION_JSON));

        List<DatedValue> result = adapter.btcDominanceDaily(365);

        assertThat(result).extracting(DatedValue::date).containsExactly(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(result).extracting(DatedValue::value).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("59.8558"), new BigDecimal("60.1"));
    }

    @Test
    void 알트_시즌_현재값() {
        server.expect(requestTo(OFFICIAL + "/v1/altcoin-season-index/latest"))
                .andExpect(header("X-CMC_PRO_API_KEY", "test-key"))
                .andRespond(withSuccess("{\"status\":{\"error_code\":0},\"data\":{\"altcoin_index\":57}}", MediaType.APPLICATION_JSON));

        assertThat(adapter.altcoinSeasonIndex()).isEqualByComparingTo("57");
    }

    @Test
    void 알트_시즌_이력은_비공식_API를_키_없이_호출하고_날짜별_마지막_점() {
        server.expect(requestTo("https://api.coinmarketcap.com/data-api/v3/altcoin-season/chart?start=1729468800&end=1760227200"))
                .andExpect(headerDoesNotExist("X-CMC_PRO_API_KEY"))
                .andRespond(withSuccess("""
                        {"data":{"points":[
                          {"name":"","altcoinIndex":"34","altcoinMarketcap":"1","timestamp":"1729468800"},
                          {"name":"","altcoinIndex":"36","altcoinMarketcap":"1","timestamp":"1729490000"},
                          {"name":"","altcoinIndex":"32","altcoinMarketcap":"1","timestamp":"1729555200"}]}}
                        """, MediaType.APPLICATION_JSON));

        List<DatedValue> result = adapter.altcoinSeasonDaily(LocalDate.of(2024, 10, 21), LocalDate.of(2025, 10, 11));

        assertThat(result).extracting(DatedValue::date).containsExactly(LocalDate.of(2024, 10, 21), LocalDate.of(2024, 10, 22));
        assertThat(result).extracting(DatedValue::value).usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("36"), new BigDecimal("32"));
        server.verify();
    }

    @Test
    void status_error_code가_0이_아니면_error_message로_실패() {
        server.expect(requestTo(OFFICIAL + "/v1/global-metrics/quotes/latest"))
                .andRespond(withSuccess("{\"status\":{\"error_code\":1008,\"error_message\":\"You've exceeded your API Key's HTTP request rate limit.\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(adapter::btcDominance).isInstanceOf(IllegalStateException.class).hasMessageContaining("rate limit");
    }

    @Test
    void 키가_비면_HTTP_호출_없이_실패() {
        for (String key : new String[]{"", null}) {
            CoinMarketCapAdapter noKey = new CoinMarketCapAdapter(builder.build(), new CoinMarketCapProperties(key));
            assertThatThrownBy(noKey::btcDominance).isInstanceOf(IllegalStateException.class);
        }
        server.verify();
    }
}
