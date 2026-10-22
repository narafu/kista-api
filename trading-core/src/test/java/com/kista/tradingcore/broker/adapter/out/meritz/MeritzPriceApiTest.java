package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.adapter.out.internal.CallIntervalGate;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class MeritzPriceApiTest {

    private static final String BASE = "https://meritz.test";
    private static final BrokerAccountRef ACCOUNT =
            new BrokerAccountRef(UUID.randomUUID(), "app-key", "secret", null, null, Broker.MERITZ);
    private static final String PRICE = "{\"rsp_cd\":\"0000\",\"data\":{\"prpr\":\"82.1100\",\"prdy_clpr\":\"103.2400\"}}";

    @Mock MeritzAuthApi authApi;

    private MockRestServiceServer server;
    private MeritzPriceApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        MeritzHttpClient http = new MeritzHttpClient(builder.build(), authApi, JsonMapper.builder().build(),
                new CallIntervalGate(0, 20_000), 1);
        api = new MeritzPriceApi(http, new MeritzExchangeRegistry());
    }

    private void expectPrice(String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/market/v1/overseas/prices")))
                .andExpect(queryParam("mrkt_div_code", "OV"))
                .andExpect(queryParam("mrkt_cls_code", "AX"))
                .andExpect(queryParam("iscd", "SOXL"))
                .andExpect(queryParam("dely_rltm_cls_code", "0"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectCandles(String body) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/market/v1/overseas/candles/days")))
                .andExpect(queryParam("mrkt_div_code", "OV"))
                .andExpect(queryParam("mrkt_cls_code", "AX"))
                .andExpect(queryParam("iscd", "SOXL"))
                .andExpect(queryParam("mod_stpr_cls_code", "1"))
                .andExpect(queryParam("from", "20261022"))
                .andExpect(queryParam("to", "20261022"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    @Test
    void price_reads_prpr_and_prev_close() {
        expectPrice(PRICE);

        PriceSnapshot s = api.getPriceSnapshot(StrategyTicker.SOXL, ACCOUNT);

        assertThat(s.current()).isEqualByComparingTo("82.1100");
        assertThat(s.prevClose()).isEqualByComparingTo("103.2400");
        server.verify();
    }

    @Test
    void closing_price_uses_us_session_bar() {
        expectCandles("{\"rsp_cd\":\"0000\",\"data\":[{\"date\":\"20261022\",\"prpr\":\"82.11\"}]}");

        BigDecimal close = api.getClosingPrice(StrategyTicker.SOXL, LocalDate.of(2026, 10, 23), ACCOUNT);

        assertThat(close).isEqualByComparingTo("82.11");
        server.verify();
    }

    @Test
    void closing_price_falls_back_to_live_when_bar_date_mismatch() {
        expectCandles("{\"rsp_cd\":\"0000\",\"data\":[{\"date\":\"20261021\",\"prpr\":\"103.24\"}]}");
        expectPrice(PRICE);

        BigDecimal close = api.getClosingPrice(StrategyTicker.SOXL, LocalDate.of(2026, 10, 23), ACCOUNT);

        assertThat(close).isEqualByComparingTo("82.1100");
        server.verify();
    }

    @Test
    void zero_price_throws() {
        expectPrice("{\"rsp_cd\":\"0000\",\"data\":{\"prpr\":\"0.0000\",\"prdy_clpr\":\"103.2400\"}}");

        assertThatThrownBy(() -> api.getPrice(StrategyTicker.SOXL, ACCOUNT)).isInstanceOf(MeritzApiException.class);
    }
}
