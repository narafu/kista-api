package com.kista.adapter.out.kis;

import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.out.BrokerTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisAuthApi 단위 테스트")
class KisAuthApiTest {

    @Mock RestTemplate kisRestTemplate;
    @Mock BrokerTokenCachePort brokerTokenCachePort;
    @Mock KisTokenCoordinator tokenCoordinator;

    KisAuthApi api;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";

    @BeforeEach
    void setUp() {
        api = new KisAuthApi(kisRestTemplate, brokerTokenCachePort, tokenCoordinator, BASE_URL);
    }

    // getToken/recoverToken의 캐시·락 동작 자체는 KisTokenCoordinatorTest가 검증한다.
    // 여기서는 KisAuthApi가 tokenCoordinator에 올바르게 위임하는지, issuer가 KIS OAuth 응답을
    // IssuedToken으로 정확히 변환하는지만 검증한다.
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
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("new-token", "2099-12-31 23:59:59")));
            ArgumentCaptor<TokenCoordinator.TokenIssuer> issuerCaptor =
                    ArgumentCaptor.forClass(TokenCoordinator.TokenIssuer.class);
            when(tokenCoordinator.obtain(eq(ACCOUNT_ID), issuerCaptor.capture())).thenReturn("new-token");

            api.getToken(ACCOUNT_ID, "key", "secret");
            TokenCoordinator.IssuedToken issued = issuerCaptor.getValue().issue();

            assertThat(issued.accessToken()).isEqualTo("new-token");
            // 2099년 만료이므로 충분히 큰 양수 초
            assertThat(issued.expiresInSeconds()).isGreaterThan(0);
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
        @DisplayName("KIS OAuth 2xx 응답 시 정상 완료 — accountId null이면 캐시 저장 생략")
        void verifyCredentials_whenKisReturns2xx_completesWithoutCaching() {
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("tok", "2099-12-31 23:59:59")));

            assertThatNoException().isThrownBy(() -> api.verifyCredentials("appKey", "appSecret", null));
            verifyNoInteractions(brokerTokenCachePort);
        }

        @Test
        @DisplayName("accountId 있고 캐시 미스 시 KIS 호출 후 토큰 캐시 저장")
        void verifyCredentials_whenAccountIdPresentAndCacheMiss_savesTokenToCache() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("tok", "2099-12-31 23:59:59")));

            assertThatNoException().isThrownBy(() -> api.verifyCredentials("appKey", "appSecret", ACCOUNT_ID));
            verify(brokerTokenCachePort).saveToken(eq(ACCOUNT_ID), eq("tok"), any());
        }

        @Test
        @DisplayName("accountId 있고 캐시 히트 시 KIS 호출 없이 정상 완료")
        void verifyCredentials_whenAccountIdPresentAndCacheHit_completesWithoutKisCall() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.of("cached-token"));

            assertThatNoException().isThrownBy(() -> api.verifyCredentials("appKey", "appSecret", ACCOUNT_ID));
            verifyNoInteractions(kisRestTemplate);
            verify(brokerTokenCachePort, never()).saveToken(any(), any(), any());
        }

        @Test
        @DisplayName("KIS OAuth 4xx 응답 시 InvalidBrokerKeyException throw")
        void verifyCredentials_whenKisReturns4xx_throwsInvalidBrokerKeyException() {
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized",
                            HttpHeaders.EMPTY, new byte[]{}, null));

            assertThatThrownBy(() -> api.verifyCredentials("badKey", "badSecret", null))
                    .isInstanceOf(Account.InvalidBrokerKeyException.class);
        }
    }
}
