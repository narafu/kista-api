package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.broker.adapter.out.internal.DbTokenCoordinator;
import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import com.kista.tradingcore.broker.domain.model.BrokerAccountVerification;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
class MeritzAuthApiTest {

    private static final String BASE = "https://meritz.test";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Mock BrokerTokenCachePort cachePort;
    @Mock DbTokenCoordinator coordinator;

    private MockRestServiceServer server;
    private MeritzAuthApi authApi;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        authApi = new MeritzAuthApi(builder.build(), JsonMapper.builder().build(), cachePort, coordinator);
    }

    private void tokenResponse(String expiresDate) {
        server.expect(requestTo(BASE + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(java.util.Map.of(
                        "grant_type", "client_credentials", "client_id", "key", "client_secret", "sec", "scope", "login")))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"access_token\":\"tok\",\"token_type\":\"Bearer\",\"expires_in\":43200,\"expires_date\":\"" + expiresDate + "\"}"));
    }

    @Test
    void expiry_uses_expires_date_not_expires_in() {
        tokenResponse(LocalDateTime.now(TimeZones.KST).plusMinutes(10).format(FMT));

        var issued = authApi.issue("key", "sec");

        assertThat(issued.accessToken()).isEqualTo("tok");
        assertThat(issued.expiresInSeconds()).isCloseTo(600L, within(5L));
    }

    @Test
    void invalid_client_id_is_credential_exception() {
        server.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"EGW00103\",\"error_description\":\"유효하지 않은 AppKey입니다.\"}"));

        assertThatThrownBy(() -> authApi.issue("key", "sec")).isInstanceOf(BrokerCredentialException.class);
    }

    @Test
    void verify_credentials_saves_token_when_account_known() {
        UUID accountId = UUID.randomUUID();
        tokenResponse(LocalDateTime.now(TimeZones.KST).plusHours(12).format(FMT));

        authApi.verifyCredentials("key", "sec", accountId);

        verify(cachePort).saveToken(eq(accountId), eq("tok"), any(OffsetDateTime.class));
    }

    @Test
    void verify_account_returns_null_codes() {
        tokenResponse(LocalDateTime.now(TimeZones.KST).plusHours(12).format(FMT));
        server.expect(requestTo(BASE + "/accounts/v1/me/overseas/currencies")).andExpect(method(HttpMethod.GET))
                .andExpect(header("authorization", "Bearer tok"))
                .andExpect(header("mac_address", "0242AC110002"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"5766\",\"data\":[{\"crcd\":\"USD\",\"frcr_amt\":\"1.0\"}]}"));

        assertThat(authApi.verifyAccount("key", "sec", null)).isEqualTo(new BrokerAccountVerification(null, null));
        server.verify();
    }

    @Test
    void verify_account_without_permission_is_permission_denied() {
        tokenResponse(LocalDateTime.now(TimeZones.KST).plusHours(12).format(FMT));
        server.expect(requestTo(BASE + "/accounts/v1/me/overseas/currencies"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"rsp_cd\":\"EGW00202\",\"rsp_msg\":\"권한 없음\"}"));

        assertThatThrownBy(() -> authApi.verifyAccount("key", "sec", null)).isInstanceOf(BrokerPermissionDeniedException.class);
    }
}
