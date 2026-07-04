package com.kista.adapter.out.kis;

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

    KisAuthApi api;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";

    @BeforeEach
    void setUp() {
        api = new KisAuthApi(kisRestTemplate, brokerTokenCachePort, BASE_URL);
    }

    @Nested
    @DisplayName("KisAuthApi — getToken")
    class TokenTests {

        @Test
        @DisplayName("캐시에 유효 토큰 있으면 KIS API 호출 없이 반환")
        void getToken_whenCacheHit_returnsCachedToken() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.of("cached-token"));

            String result = api.getToken(ACCOUNT_ID, "key", "secret");

            assertThat(result).isEqualTo("cached-token");
            verifyNoInteractions(kisRestTemplate);
        }

        @Test
        @DisplayName("캐시 미스 시 KIS API 호출하여 신규 토큰 반환")
        void getToken_whenCacheMiss_returnsNewToken() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("new-token", "2099-12-31 23:59:59")));

            String result = api.getToken(ACCOUNT_ID, "key", "secret");

            assertThat(result).isEqualTo("new-token");
        }

        @Test
        @DisplayName("캐시 미스 시 발급 토큰을 account_id와 함께 saveToken으로 캐시에 저장")
        void getToken_whenCacheMiss_savesTokenToCache() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("new-token", "2099-12-31 23:59:59")));

            api.getToken(ACCOUNT_ID, "key", "secret");

            ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(brokerTokenCachePort).saveToken(eq(ACCOUNT_ID), eq("new-token"), expiresCaptor.capture());
            // KIS 응답은 KST(+09:00)이므로 offset이 +09:00이어야 함
            assertThat(expiresCaptor.getValue().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        }

        @Test
        @DisplayName("캐시 만료 1분 전 임박 토큰은 재발급 — findValidToken에 now+1분 전달")
        void getToken_uses1MinBuffer_forCacheCheck() {
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenReturn(ResponseEntity.ok(new KisAuthApi.TokenResponse("new-token", "2099-12-31 23:59:59")));

            api.getToken(ACCOUNT_ID, "key", "secret");

            // double-check 패턴으로 findValidToken이 2회 호출됨 (1차: 락 전, 2차: 락 후)
            ArgumentCaptor<OffsetDateTime> thresholdCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(brokerTokenCachePort, times(2)).findValidToken(eq(ACCOUNT_ID), thresholdCaptor.capture());
            // 두 번 모두 threshold가 현재 시각보다 최소 59초 이상 미래여야 함 (1분 버퍼)
            thresholdCaptor.getAllValues().forEach(t ->
                    assertThat(t).isAfter(OffsetDateTime.now().plusSeconds(59)));
        }

        @Test
        @DisplayName("캐시 miss 후 lock 내 2차 조회(double-check)에서 hit 시 KIS API 미호출")
        void getToken_doubleCheck_preventsRedundantIssue() {
            // 1차 miss, 2차 hit (다른 스레드가 이미 발급한 상황 시뮬레이션)
            when(brokerTokenCachePort.findValidToken(eq(ACCOUNT_ID), any()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of("concurrent-token"));

            String result = api.getToken(ACCOUNT_ID, "key", "secret");

            assertThat(result).isEqualTo("concurrent-token");
            verifyNoInteractions(kisRestTemplate);
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
        @DisplayName("KIS OAuth 4xx 응답 시 InvalidKisKeyException throw")
        void verifyCredentials_whenKisReturns4xx_throwsInvalidKisKeyException() {
            when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisAuthApi.TokenResponse.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.UNAUTHORIZED, "Unauthorized",
                            HttpHeaders.EMPTY, new byte[]{}, null));

            assertThatThrownBy(() -> api.verifyCredentials("badKey", "badSecret", null))
                    .isInstanceOf(Account.InvalidKisKeyException.class);
        }
    }
}
