package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UpbitAuthApiTest {

    private static final String BASE_URL = "https://api.upbit.com";
    private MockRestServiceServer server;
    private UpbitAuthApi api;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        api = new UpbitAuthApi(new UpbitHttpClient(builder.build(), JsonMapper.builder().build(), () -> "n"));
    }

    @Test
    void verifyAccountReturnsExpiryOfMatchingKey() {
        server.expect(once(), requestTo(BASE_URL + "/v1/accounts"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE_URL + "/v1/api_keys"))
                .andRespond(withSuccess("""
                        [{"access_key":"other","expire_at":"2026-01-01T00:00:00+09:00"},
                         {"access_key":"my-key","expire_at":"2026-06-25T11:22:54+09:00"}]
                        """, MediaType.APPLICATION_JSON));

        BrokerAccountVerification v = api.verifyAccount("my-key", "secret-0123456789012345678901234567890", "ignored");

        assertThat(v.brokerAccountCode()).isNull();
        assertThat(v.apiKeyExpiresAt()).isEqualTo(Instant.parse("2026-06-25T02:22:54Z"));
        assertThat(api.supports()).isEqualTo(Broker.UPBIT);
        server.verify();
    }

    @Test
    void verifyAccountWithoutMatchingKeyHasNullExpiry() {
        server.expect(requestTo(BASE_URL + "/v1/accounts")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/api_keys")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(api.verifyAccount("my-key", "s", null).apiKeyExpiresAt()).isNull();
    }

    @Test
    void ipNotAllowedSurfacesAsSpecificException() {
        server.expect(requestTo(BASE_URL + "/v1/accounts")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"name\":\"no_authorization_ip\",\"message\":\"x\"}}"));

        assertThatThrownBy(() -> api.verifyCredentials("my-key", "s", null)).isInstanceOf(BrokerIpNotAllowedException.class);
    }

    @Test
    void verifyCredentialsWithoutKeys_rejectedBeforeSigning() {
        // 등록 계좌 id만으로 테스트(키 null)해도 서명에 null이 들어가면 안 된다 — HTTP 호출 없이 422
        assertThatThrownBy(() -> api.verifyCredentials(null, null, java.util.UUID.randomUUID()))
                .isInstanceOf(com.kista.tradingcore.broker.domain.model.BrokerCredentialException.class);
        server.verify();
    }
}
