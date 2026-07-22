package com.kista.adapter.out.toss;

import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.domain.model.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossAuthApi 단위 테스트")
class TossAuthApiTest {

    @Mock RestTemplate tossRestTemplate;
    @Mock TossDistributedTokenCoordinator tokenCoordinator;

    TossAuthApi api;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE_URL = "https://openapi.tossinvest.com";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String ADMIN_CLIENT_ID = "admin-client-id";
    private static final String ADMIN_CLIENT_SECRET = "admin-client-secret";

    @BeforeEach
    void setUp() {
        api = new TossAuthApi(tossRestTemplate, tokenCoordinator,
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

    @Nested
    @DisplayName("TossAuthApi — getToken")
    class TokenTests {

        @Test
        @DisplayName("캐시 히트 시 tossRestTemplate 미호출")
        void getToken_cacheHit_noApiCall() {
            doReturn("cached-token").when(tokenCoordinator)
                    .obtain(eq(ACCOUNT_ID), any());

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("cached-token");
            verifyNoInteractions(tossRestTemplate);
        }

        @Test
        @DisplayName("Redis canonical miss 시 OAuth token과 expiresIn을 coordinator에 전달")
        void getToken_cacheMiss_fetchAndCache() {
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("new-token", 86400L)));

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("new-token");
            verify(tossRestTemplate).exchange(
                    anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class));
        }

        @Test
        @DisplayName("REST 오류 시 Account.InvalidBrokerKeyException throw")
        void getToken_restClientException_throwsInvalidBrokerKeyException() {
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized",
                            org.springframework.http.HttpHeaders.EMPTY, new byte[]{}, null));

            assertThatThrownBy(() -> api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
        }

        @Test
        @DisplayName("캐시 미스 후 double-check 히트 시 tossRestTemplate 미호출")
        void getToken_doubleCheckHit_noApiCall() {
            doReturn("concurrent-token").when(tokenCoordinator)
                    .obtain(eq(ACCOUNT_ID), any());

            String result = api.getToken(ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET);

            assertThat(result).isEqualTo("concurrent-token");
            verifyNoInteractions(tossRestTemplate);
        }
    }

    @Test
    @DisplayName("stale 관리자 401 복구는 현재 관리자 토큰을 반환")
    void recoverAdminToken_returnsCurrentToken_whenRejectedTokenIsStale() {
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("admin-token-1", 86400L)));

        String current = api.getAdminToken();
        when(tokenCoordinator.recoverAdminToken(eq("stale-admin-token"), any()))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", false));
        TokenCoordinator.RecoveredToken recovered = api.recoverAdminToken("stale-admin-token");

        assertThat(current).isEqualTo("admin-token-1");
        assertThat(recovered.accessToken()).isEqualTo("admin-token-1");
        verify(tossRestTemplate, times(1)).exchange(
                anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class));
    }

    @Test
    @DisplayName("최근 발급한 관리자 토큰의 401 복구는 같은 발급 세대를 재사용")
    void recoverAdminToken_reusesRecentlyIssuedGeneration() {
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("admin-token-1", 86400L)));

        String issued = api.getAdminToken();
        when(tokenCoordinator.recoverAdminToken(eq(issued), any()))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", true));
        TokenCoordinator.RecoveredToken recovered = api.recoverAdminToken(issued);

        assertThat(recovered.accessToken()).isEqualTo("admin-token-1");
        verify(tossRestTemplate, times(1)).exchange(
                anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class));
    }

    @Test
    @DisplayName("계좌 401 복구를 rejected token과 함께 분산 coordinator에 위임")
    void recoverToken_delegatesToDistributedCoordinator() {
        when(tokenCoordinator.recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any()))
                .thenReturn(new TokenCoordinator.RecoveredToken("coordinated-token", false));

        TokenCoordinator.RecoveredToken recovered = api.recoverToken(
                ACCOUNT_ID, CLIENT_ID, CLIENT_SECRET, "rejected-token");

        assertThat(recovered.accessToken()).isEqualTo("coordinated-token");
        verify(tokenCoordinator).recover(
                eq(ACCOUNT_ID), eq("rejected-token"), any());
        verifyNoInteractions(tossRestTemplate);
    }

    @Nested
    @DisplayName("BrokerConnectionTestPort — verifyAccount")
    class ConnectionTestTests {

        @Test
        @DisplayName("정상 인증 및 계좌 조회 시 첫 번째 accountSeq 반환")
        @SuppressWarnings("unchecked")
        void verifyAccount_success_returnsSeq() {
            // OAuth 토큰 발급 stub
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("temp-token", 86400L)));
            // 계좌 목록 조회 stub
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(new TossResult<>(
                            List.of(new TossAuthApi.AccountItem(42, "1234567890"))
                    )));

            String result = api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null);

            assertThat(result).isEqualTo("42");
        }

        @Test
        @DisplayName("OAuth 인증 실패 시 Account.InvalidBrokerKeyException throw")
        void verifyAccount_authFails_throwsInvalidBrokerKeyException() {
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized",
                            org.springframework.http.HttpHeaders.EMPTY, new byte[]{}, null));

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
        }

        @Test
        @DisplayName("계좌 목록 비어있으면 Account.InvalidBrokerKeyException throw")
        @SuppressWarnings("unchecked")
        void verifyAccount_emptyAccounts_throwsInvalidBrokerKeyException() {
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("temp-token", 86400L)));
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(new TossResult<>(List.of())));

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
        }

        @Test
        @DisplayName("계좌 조회 REST 오류 시 Account.InvalidBrokerKeyException throw")
        @SuppressWarnings("unchecked")
        void verifyAccount_accountsFetchFails_throwsInvalidBrokerKeyException() {
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(TossAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new TossAuthApi.TokenResponse("temp-token", 86400L)));
            when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.FORBIDDEN, "Forbidden",
                            org.springframework.http.HttpHeaders.EMPTY, new byte[]{}, null));

            assertThatThrownBy(() -> api.verifyAccount(CLIENT_ID, CLIENT_SECRET, null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
        }
    }
}
