package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.tradingcore.broker.adapter.out.internal.DbTokenCoordinator;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisAuthApi 단위 테스트")
class KisAuthApiTest {

    @Mock DbTokenCoordinator tokenCoordinator;
    @Mock KisAdminTokenCoordinator adminCoordinator;

    RestClient.Builder restClientBuilder;
    MockRestServiceServer server;
    KisAuthApi api;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";

    @BeforeEach
    void setUp() {
        // baseUrl은 이제 RestClient(=KisConfig 빈)에 고정 — 테스트도 KisConfig와 동일하게 builder에 설정
        restClientBuilder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        api = new KisAuthApi(restClientBuilder.build(), tokenCoordinator, adminCoordinator);
    }

    private void expectOAuthToken(String accessToken, String expiredAt) {
        server.expect(requestTo(BASE_URL + "/oauth2/tokenP"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"%s","access_token_token_expired":"%s"}
                        """.formatted(accessToken, expiredAt), MediaType.APPLICATION_JSON));
    }

    private void expectOAuthTokenFails() {
        server.expect(requestTo(BASE_URL + "/oauth2/tokenP"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest().body("""
                        {"error_description":"Unauthorized"}
                        """));
    }

    @Nested
    @DisplayName("KisAuthApi — getToken / recoverToken")
    class TokenTests {

        @Test
        @DisplayName("getToken은 tokenCoordinator.obtain 결과를 그대로 반환한다")
        void getToken_delegatesToCoordinator() {
            when(tokenCoordinator.obtain(eq(ACCOUNT_ID), any())).thenReturn("coordinated-token");

            String result = api.getToken(ACCOUNT_ID, "key", "secret");

            assertThat(result).isEqualTo("coordinated-token");
        }

        @Test
        @DisplayName("getToken이 넘기는 issuer는 KIS OAuth를 호출해 IssuedToken(accessToken, expiresInSeconds)으로 변환한다")
        void getToken_issuerConvertsKisOAuthResponse_toIssuedToken() {
            expectOAuthToken("new-token", "2099-12-31 23:59:59");
            ArgumentCaptor<TokenCoordinator.TokenIssuer> issuerCaptor =
                    ArgumentCaptor.forClass(TokenCoordinator.TokenIssuer.class);
            when(tokenCoordinator.obtain(eq(ACCOUNT_ID), issuerCaptor.capture())).thenReturn("new-token");

            api.getToken(ACCOUNT_ID, "key", "secret");
            TokenCoordinator.IssuedToken issued = issuerCaptor.getValue().issue();

            assertThat(issued.accessToken()).isEqualTo("new-token");
            // 2099년 만료이므로 충분히 큰 양수 초
            assertThat(issued.expiresInSeconds()).isGreaterThan(0);
            server.verify();
        }

        @Test
        @DisplayName("recoverToken은 tokenCoordinator.recover 결과를 그대로 반환한다")
        void recoverToken_delegatesToCoordinator() {
            when(tokenCoordinator.recover(eq(ACCOUNT_ID), eq("rejected-token"), any()))
                    .thenReturn(new TokenCoordinator.RecoveredToken("fresh-token", true));

            TokenCoordinator.RecoveredToken result =
                    api.recoverToken(ACCOUNT_ID, "key", "secret", "rejected-token");

            assertThat(result.accessToken()).isEqualTo("fresh-token");
            assertThat(result.freshlyIssued()).isTrue();
        }

        @Test
        @DisplayName("nil UUID(관리자 id)면 getToken/recoverToken이 관리자 코디네이터(앱키 기반)로 간다")
        void adminId_routesToAdminCoordinator() {
            when(adminCoordinator.obtain(eq("admin-key"), any())).thenReturn("admin-token");
            when(adminCoordinator.recover(eq("admin-key"), eq("rejected"), any()))
                    .thenReturn(new TokenCoordinator.RecoveredToken("admin-fresh", true));

            assertThat(api.getToken(KisAdminTokenCoordinator.ADMIN_TOKEN_ID, "admin-key", "s")).isEqualTo("admin-token");
            assertThat(api.recoverToken(KisAdminTokenCoordinator.ADMIN_TOKEN_ID, "admin-key", "s", "rejected").accessToken())
                    .isEqualTo("admin-fresh");
            verifyNoInteractions(tokenCoordinator);
        }

        @Test
        @DisplayName("일반 계좌 id는 관리자 코디네이터를 쓰지 않는다")
        void accountId_routesToAccountCoordinator() {
            when(tokenCoordinator.obtain(eq(ACCOUNT_ID), any())).thenReturn("t");

            api.getToken(ACCOUNT_ID, "key", "secret");

            verifyNoInteractions(adminCoordinator);
        }

        @Test
        @DisplayName("parseExpiry: KST 문자열을 +09:00 OffsetDateTime으로 파싱")
        void parseExpiry_parsesKstStringCorrectly() {
            OffsetDateTime result = api.parseExpiry("2024-06-16 05:17:02");

            assertThat(result.getYear()).isEqualTo(2024);
            assertThat(result.getMonthValue()).isEqualTo(6);
            assertThat(result.getDayOfMonth()).isEqualTo(16);
            assertThat(result.getHour()).isEqualTo(5);
            assertThat(result.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        }
    }

    @Nested
    @DisplayName("BrokerConnectionTestPort — verifyCredentials")
    class ConnectionTests {

        @Test
        @DisplayName("KIS OAuth 2xx 응답 시 정상 완료")
        void verifyCredentials_whenKisReturns2xx_completes() {
            expectOAuthToken("tok", "2099-12-31 23:59:59");

            assertThatNoException().isThrownBy(() -> api.verifyCredentials("appKey", "appSecret"));
            server.verify();
        }

        @Test
        @DisplayName("KIS OAuth 4xx 응답 시 InvalidBrokerKeyException throw")
        void verifyCredentials_whenKisReturns4xx_throwsInvalidBrokerKeyException() {
            expectOAuthTokenFails();

            assertThatThrownBy(() -> api.verifyCredentials("badKey", "badSecret"))
                    .isInstanceOf(BrokerCredentialException.class);
            server.verify();
        }
    }
}
