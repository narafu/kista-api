package com.kista.broker.adapter.out.toss;

import com.kista.broker.adapter.out.internal.TokenCoordinator;
import com.kista.domain.model.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossAuthApi 단위 테스트")
class TossAuthApiTest {

    @Mock TossDistributedTokenCoordinator tokenCoordinator;

    RestClient.Builder restClientBuilder;
    MockRestServiceServer server;
    TossAuthApi api;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE_URL = "https://openapi.tossinvest.com";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String ADMIN_CLIENT_ID = "admin-client-id";
    private static final String ADMIN_CLIENT_SECRET = "admin-client-secret";

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        api = new TossAuthApi(restClientBuilder.build(), tokenCoordinator,
                BASE_URL, ADMIN_CLIENT_ID, ADMIN_CLIENT_SECRET);
        stubCoordinatorIssuance();
    }

    private void stubCoordinatorIssuance() {
        lenient().when(tokenCoordinator.obtain(any(), any())).thenAnswer(invocation -> {
            TokenCoordinator.TokenIssuer issuer = invocation.getArgument(1);
            return issuer.issue().accessToken();
        });
        lenient().when(tokenCoordinator.getAdminToken(any())).thenAnswer(invocation -> {
            TokenCoordinator.TokenIssuer issuer = invocation.getArgument(0);
            return issuer.issue().accessToken();
        });
    }

    private void expectOAuthToken(String accessToken, long expiresIn) {
        server.expect(requestTo(BASE_URL + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"access_token":"%s","expires_in":%d}
                        """.formatted(accessToken, expiresIn), MediaType.APPLICATION_JSON));
    }

    private void expectOAuthTokenUnauthorized() {
        server.expect(requestTo(BASE_URL + "/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withUnauthorizedRequest());
    }

    private void expectAccountsList(String body) {
        server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectAccountsListForbidden() {
        server.expect(requestTo(BASE_URL + "/api/v1/accounts"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
    }

    @Nested
    @DisplayName("TossAuthApi — getToken")
    class TokenTests {

        @Test
        @DisplayName("캐시 히트 시 tossRestClient 미호출")
        void getToken_cacheHit_noApiCall() {
            doReturn("cached-token").when(tokenCoordinator)
                    .obtain(eq(ACCOUNT_ID), any());

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("cached-token");
            server.verify(); // 등록된 expectation 없음 — HTTP 호출 없었음을 함께 확인
        }

        @Test
        @DisplayName("Redis canonical miss 시 OAuth token과 expiresIn을 coordinator에 전달")
        void getToken_cacheMiss_fetchAndCache() {
            expectOAuthToken("new-token", 86400L);

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("new-token");
            server.verify();
        }

        @Test
        @DisplayName("REST 오류 시 Account.InvalidBrokerKeyException throw")
        void getToken_restClientException_throwsInvalidBrokerKeyException() {
            expectOAuthTokenUnauthorized();

            assertThatThrownBy(() -> api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
            server.verify();
        }

        @Test
        @DisplayName("캐시 미스 후 double-check 히트 시 tossRestClient 미호출")
        void getToken_doubleCheckHit_noApiCall() {
            doReturn("concurrent-token").when(tokenCoordinator)
                    .obtain(eq(ACCOUNT_ID), any());

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("concurrent-token");
            server.verify();
        }
    }

    @Test
    @DisplayName("stale 관리자 401 복구는 현재 관리자 토큰을 반환")
    void recoverAdminToken_returnsCurrentToken_whenRejectedTokenIsStale() {
        expectOAuthToken("admin-token-1", 86400L);

        String current = api.getAdminToken();
        when(tokenCoordinator.recoverAdminToken(eq("stale-admin-token"), any(), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", false));
        TokenCoordinator.RecoveredToken recovered = api.recoverAdminToken("stale-admin-token", false);

        assertThat(current).isEqualTo("admin-token-1");
        assertThat(recovered.accessToken()).isEqualTo("admin-token-1");
        server.verify(); // OAuth 호출 1회만 발생 — coordinator가 두 번째 호출을 캐시로 처리
    }

    @Test
    @DisplayName("최근 발급한 관리자 토큰의 401 복구는 같은 발급 세대를 재사용")
    void recoverAdminToken_reusesRecentlyIssuedGeneration() {
        expectOAuthToken("admin-token-1", 86400L);

        String issued = api.getAdminToken();
        when(tokenCoordinator.recoverAdminToken(eq(issued), any(), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", true));
        TokenCoordinator.RecoveredToken recovered = api.recoverAdminToken(issued, false);

        assertThat(recovered.accessToken()).isEqualTo("admin-token-1");
        server.verify();
    }

    @Test
    @DisplayName("관리자 토큰 복구 forceReissue=true는 coordinator에 그대로 전달")
    void recoverAdminToken_forwardsForceReissueFlag() {
        when(tokenCoordinator.recoverAdminToken(eq("stale-admin-token"), any(), eq(true)))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-2", true));

        TokenCoordinator.RecoveredToken recovered = api.recoverAdminToken("stale-admin-token", true);

        assertThat(recovered.accessToken()).isEqualTo("admin-token-2");
        verify(tokenCoordinator).recoverAdminToken(eq("stale-admin-token"), any(), eq(true));
    }

    @Test
    @DisplayName("계좌 401 복구를 rejected token·forceReissue와 함께 분산 coordinator에 위임")
    void recoverToken_delegatesToDistributedCoordinator() {
        when(tokenCoordinator.recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any(), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("coordinated-token", false));

        TokenCoordinator.RecoveredToken recovered = api.recoverToken(
                ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET, "rejected-token", false);

        assertThat(recovered.accessToken()).isEqualTo("coordinated-token");
        verify(tokenCoordinator).recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any(), eq(false));
        server.verify();
    }

    @Test
    @DisplayName("계좌 401 복구 forceReissue=true는 coordinator에 그대로 전달")
    void recoverToken_forwardsForceReissueFlag() {
        when(tokenCoordinator.recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any(), eq(true)))
                .thenReturn(new TokenCoordinator.RecoveredToken("reissued-token", true));

        TokenCoordinator.RecoveredToken recovered = api.recoverToken(
                ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET, "rejected-token", true);

        assertThat(recovered.accessToken()).isEqualTo("reissued-token");
        verify(tokenCoordinator).recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any(), eq(true));
    }

    @Nested
    @DisplayName("BrokerConnectionTestPort — verifyAccount")
    class ConnectionTestTests {

        @Test
        @DisplayName("정상 인증 및 계좌 조회 시 첫 번째 accountSeq 반환")
        void verifyAccount_success_returnsSeq() {
            expectOAuthToken("temp-token", 86400L);
            expectAccountsList("""
                    {"result":[{"accountSeq":42,"accountNo":"1234567890"}]}
                    """);

            String result = api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null);

            assertThat(result).isEqualTo("42");
            server.verify();
        }

        @Test
        @DisplayName("OAuth 인증 실패 시 Account.InvalidBrokerKeyException throw")
        void verifyAccount_authFails_throwsInvalidBrokerKeyException() {
            expectOAuthTokenUnauthorized();

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
            server.verify();
        }

        @Test
        @DisplayName("계좌 목록 비어있으면 Account.InvalidBrokerKeyException throw")
        void verifyAccount_emptyAccounts_throwsInvalidBrokerKeyException() {
            expectOAuthToken("temp-token", 86400L);
            expectAccountsList("""
                    {"result":[]}
                    """);

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
            server.verify();
        }

        @Test
        @DisplayName("계좌 조회 REST 오류 시 Account.InvalidBrokerKeyException throw")
        void verifyAccount_accountsFetchFails_throwsInvalidBrokerKeyException() {
            expectOAuthToken("temp-token", 86400L);
            expectAccountsListForbidden();

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
            server.verify();
        }
    }
}
