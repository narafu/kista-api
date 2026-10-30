package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.adapter.out.internal.CallIntervalGate;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.meritz.MeritzApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class MeritzHttpClientTest {

    private static final String BASE = "https://meritz.test";
    private static final String PATH = "/accounts/v1/me/overseas/holdings";
    private static final BrokerAccountRef ACCOUNT =
            new BrokerAccountRef(UUID.randomUUID(), "app-key", "secret", null, null, Broker.MERITZ);

    @Mock MeritzAuthApi authApi;

    private MockRestServiceServer server;
    private MeritzHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        // 간격 0ms·백오프 1ms — 판정 로직만 검증
        client = new MeritzHttpClient(builder.build(), authApi, JsonMapper.builder().build(), new CallIntervalGate(0, 20_000), 1);
    }

    private void respond(HttpMethod method, HttpStatus status, String body) {
        server.expect(requestTo(BASE + PATH)).andExpect(method(method))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private static String code(String rspCd) {
        return "{\"rsp_cd\":\"" + rspCd + "\",\"rsp_msg\":\"m\"}";
    }

    record Row(String crcd, String frcr_amt) {}

    @Test
    void business_error_on_http_500_is_rejected_with_rsp_cd() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"rsp_cd\":\"7936\",\"rsp_msg\":\"해당데이터가 없습니다.\",\"rsp_sub_msg\":\"조회시작일\"}");

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> {
                    assertThat(e.isRejected()).isTrue();
                    assertThat(e.rspCd()).isEqualTo("7936");
                });
    }

    @Test
    void success_body_on_http_500_is_success() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"rsp_cd\":\"5766\",\"data\":[{\"crcd\":\"USD\",\"frcr_amt\":\"6813.220\"}]}");

        MeritzResponse res = client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>());

        assertThat(res.success()).isTrue();
        assertThat(client.rowList(res, Row.class)).containsExactly(new Row("USD", "6813.220"));
    }

    @Test
    void error_body_on_http_200_is_rejected() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.OK, code("9999"));

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isTrue());
    }

    @Test
    void io_error_is_unknown_outcome() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(requestTo(BASE + PATH)).andRespond(withException(new IOException("reset")));

        assertThatThrownBy(() -> client.post(PATH, ACCOUNT, Map.of("a", "b")))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void unparseable_body_is_unknown_outcome() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(requestTo(BASE + PATH)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>gw</html>"));

        assertThatThrownBy(() -> client.post(PATH, ACCOUNT, Map.of("a", "b")))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isFalse());
    }

    @Test
    void token_failure_is_rejected_before_send() {
        when(authApi.getToken(ACCOUNT)).thenThrow(new MeritzApiException(null, "issue failed", null, false));

        assertThatThrownBy(() -> client.post(PATH, ACCOUNT, Map.of("a", "b")))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isTrue());
        server.verify();
    }

    @Test
    void expired_token_reissues_once_then_succeeds() {
        when(authApi.getToken(ACCOUNT)).thenReturn("old");
        when(authApi.recoverToken(ACCOUNT, "old")).thenReturn(new TokenCoordinator.RecoveredToken("new", true));
        server.expect(requestTo(BASE + PATH)).andExpect(header("authorization", "Bearer old"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(code("EGW00123")));
        server.expect(requestTo(BASE + PATH)).andExpect(header("authorization", "Bearer new"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(code("0000")));

        assertThat(client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()).rspCd()).isEqualTo("0000");
        server.verify();
    }

    @Test
    void expired_token_on_post_reissues_once_then_succeeds() {
        when(authApi.getToken(ACCOUNT)).thenReturn("old");
        when(authApi.recoverToken(ACCOUNT, "old")).thenReturn(new TokenCoordinator.RecoveredToken("new", true));
        respond(HttpMethod.POST, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00121"));
        respond(HttpMethod.POST, HttpStatus.OK, code("0001"));

        assertThat(client.post(PATH, ACCOUNT, Map.of("odqt", "1")).rspCd()).isEqualTo("0001");
        verify(authApi).recoverToken(ACCOUNT, "old");
        server.verify();
    }

    @Test
    void expired_token_twice_is_rejected() {
        when(authApi.getToken(ACCOUNT)).thenReturn("old");
        when(authApi.recoverToken(ACCOUNT, "old")).thenReturn(new TokenCoordinator.RecoveredToken("new", true));
        respond(HttpMethod.POST, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00123"));
        respond(HttpMethod.POST, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00123"));

        assertThatThrownBy(() -> client.post(PATH, ACCOUNT, Map.of("odqt", "1")))
                .isInstanceOfSatisfying(MeritzApiException.class, e -> assertThat(e.isRejected()).isTrue());
        server.verify();
    }

    @Test
    void rate_limit_retries_get_but_not_post() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00200"));
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00200"));
        respond(HttpMethod.GET, HttpStatus.OK, code("0000"));
        assertThat(client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()).success()).isTrue();
        server.verify();

        server.reset();
        respond(HttpMethod.POST, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00200"));
        assertThatThrownBy(() -> client.post(PATH, ACCOUNT, Map.of("odqt", "1"))).isInstanceOf(BrokerRateLimitException.class);
        server.verify(); // 요청 1건만
    }

    @Test
    void rate_limit_on_get_gives_up_after_two_retries() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(ExpectedCount.times(3), requestTo(BASE + PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(code("EGW00200")));

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>())).isInstanceOf(BrokerRateLimitException.class);
        server.verify();
    }

    @Test
    void permission_denied_maps_to_broker_permission_exception() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR, code("0760"));
        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>())).isInstanceOf(BrokerPermissionDeniedException.class);

        server.reset();
        respond(HttpMethod.GET, HttpStatus.INTERNAL_SERVER_ERROR, code("EGW00103"));
        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()))
                .isInstanceOf(BrokerCredentialException.class).isNotInstanceOf(BrokerPermissionDeniedException.class);
    }

    @Test
    void paging_sends_tr_cont_key_verbatim_and_stops_on_5766() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(requestTo(BASE + PATH + "?crcd=USD"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"5762\",\"tr_cont\":\"1\",\"tr_cont_key\":\"ABC   \",\"data\":[{\"crcd\":\"A\",\"frcr_amt\":1}]}"));
        server.expect(requestTo(BASE + PATH + "?crcd=USD&tr_cont=1&tr_cont_key=ABC%20%20%20"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"5766\",\"tr_cont\":\"0\",\"data\":[{\"crcd\":\"B\",\"frcr_amt\":\"2.5\"}]}"));

        LinkedMultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("crcd", "USD");
        List<Row> rows = client.getAllPages(PATH, ACCOUNT, params, Row.class);

        assertThat(rows).containsExactly(new Row("A", "1"), new Row("B", "2.5"));
        assertThat(params).doesNotContainKey("tr_cont"); // 호출부 파라미터 불변
        server.verify();
    }

    @Test
    void paging_stops_at_50_pages() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(ExpectedCount.times(50), requestTo(org.hamcrest.Matchers.startsWith(BASE + PATH)))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"5762\",\"tr_cont\":\"1\",\"tr_cont_key\":\"K\",\"data\":[{\"crcd\":\"A\"}]}"));

        assertThat(client.getAllPages(PATH, ACCOUNT, new LinkedMultiValueMap<>(), Row.class)).hasSize(50);
        server.verify();
    }

    @Test
    void headers_include_bearer_and_mac_address() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        server.expect(requestTo(BASE + PATH))
                .andExpect(header("authorization", "Bearer tok"))
                .andExpect(header("mac_address", "0242AC110002"))
                .andExpect(content().json("{\"odqt\":\"1\"}"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(code("0001")));

        client.post(PATH, ACCOUNT, Map.of("odqt", "1"));
        server.verify();
    }

    @Test
    void empty_data_gives_empty_list_and_numbers_parse() {
        when(authApi.getToken(ACCOUNT)).thenReturn("tok");
        respond(HttpMethod.GET, HttpStatus.OK, code("5820"));

        assertThat(client.rowList(client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>()), Row.class)).isEmpty();
        assertThat(MeritzResponse.decimal(" ")).isNull();
        assertThat(MeritzResponse.decimal("106.04440000")).isEqualByComparingTo(new BigDecimal("106.0444"));
        assertThat(MeritzResponse.wholeQuantity("417.0000000000")).isEqualTo(417);
    }
}
