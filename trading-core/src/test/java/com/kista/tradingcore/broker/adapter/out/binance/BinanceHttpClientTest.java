package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.*;
import com.kista.tradingcore.broker.domain.model.binance.BinanceApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BinanceHttpClientTest {

    private static final String BASE = "https://api.binance.com";
    private static final long NOW = 1499827319559L;
    private static final BrokerAccountRef AUTH = new BrokerAccountRef(null, "ak", "sk", null, null, Broker.BINANCE);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() { };

    private MockRestServiceServer server;
    private BinanceHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BinanceHttpClient(builder.build(), JsonMapper.builder().build(), BASE,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void signMatchesOfficialVector() {
        String payload = "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000&timestamp=1499827319559";
        assertThat(BinanceHttpClient.sign("NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j", payload))
                .isEqualTo("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71");
    }

    @Test
    void signedGetPutsParamsTimestampAndSignatureInQueryWithApiKeyHeader() {
        String payload = "omitZeroBalances=true&recvWindow=5000&timestamp=1499827319559";
        server.expect(requestTo(BASE + "/api/v3/account?" + payload + "&signature=" + BinanceHttpClient.sign("sk", payload)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-MBX-APIKEY", "ak"))
                .andRespond(withSuccess("{}", APPLICATION_JSON));
        client.getSigned("/api/v3/account", params("omitZeroBalances", "true"), AUTH, MAP);
        server.verify();
    }

    @Test
    void signedPostSendsParamsInQueryWithEmptyBody() {
        String payload = "symbol=BTCUSDT&quoteOrderQty=10.5&recvWindow=5000&timestamp=1499827319559";
        server.expect(requestTo(BASE + "/api/v3/order?" + payload + "&signature=" + BinanceHttpClient.sign("sk", payload)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-MBX-APIKEY", "ak"))
                .andExpect(content().string(""))
                .andRespond(withSuccess("{}", APPLICATION_JSON));
        client.postSigned("/api/v3/order", params("symbol", "BTCUSDT", "quoteOrderQty", "10.5"), AUTH, MAP);
        server.verify();
    }

    @Test
    void publicGetHasNoSignature() {
        server.expect(requestTo(BASE + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andExpect(headerDoesNotExist("X-MBX-APIKEY"))
                .andRespond(withSuccess("{}", APPLICATION_JSON));
        client.getPublic("/api/v3/exchangeInfo", params("symbol", "BTCUSDT"), MAP);
        server.verify();
    }

    @Test
    void blankSecretRejectedBeforeSigning() {
        server.expect(never(), anything());
        BrokerAccountRef blank = new BrokerAccountRef(null, "ak", " ", null, null, Broker.BINANCE);
        assertThatThrownBy(() -> client.getSigned("/api/v3/account", params(), blank, MAP))
                .isExactlyInstanceOf(BrokerCredentialException.class);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "400,-2015,CREDENTIAL", "401,-2014,CREDENTIAL", "400,-1022,CREDENTIAL",
            "429,-1003,RATE", "418,-1003,RATE",
            "500,,UNKNOWN", "503,,UNKNOWN", "400,-1006,UNKNOWN", "408,-1007,UNKNOWN",
            "400,-2010,REJECTED", "400,-1013,REJECTED", "400,-1021,REJECTED", "400,-2013,REJECTED", "403,,REJECTED"})
    void errorMapping(int status, Integer code, String kind) {
        String body = code == null ? "<html>" : "{\"code\":" + code + ",\"msg\":\"x\"}";
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status)).contentType(APPLICATION_JSON).body(body));
        Throwable e = org.assertj.core.api.Assertions.catchThrowable(() -> client.getSigned("/api/v3/account", params(), AUTH, MAP));
        switch (kind) {
            case "CREDENTIAL" -> assertThat(e).isExactlyInstanceOf(BrokerCredentialException.class);
            case "RATE" -> assertThat(e).isExactlyInstanceOf(BrokerRateLimitException.class);
            case "UNKNOWN" -> {
                assertThat(e).isInstanceOf(BinanceApiException.class);
                assertThat(((BinanceApiException) e).isRejected()).isFalse();
                assertThat(BrokerApiException.isRejection(e)).isFalse();
            }
            default -> {
                assertThat(e).isInstanceOf(BinanceApiException.class);
                BinanceApiException b = (BinanceApiException) e;
                assertThat(b.isRejected()).isTrue();
                assertThat(b.code()).isEqualTo(code);
            }
        }
    }

    @Test
    void ioFailureIsUnknown() {
        server.expect(anything()).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> client.getSigned("/api/v3/account", params(), AUTH, MAP))
                .isInstanceOfSatisfying(BinanceApiException.class, e -> {
                    assertThat(e.isRejected()).isFalse();
                    assertThat(e.status()).isZero();
                });
    }

    private static LinkedHashMap<String, String> params(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
