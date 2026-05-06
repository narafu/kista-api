package com.kista.adapter.out.kis;

import com.kista.domain.port.out.KisTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisTokenAdapter 토큰 캐시 검증")
class KisTokenAdapterTest {

    @Mock
    RestTemplate kisRestTemplate;

    @Mock
    KisTokenCachePort kisTokenCachePort;

    KisTokenAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://api.test.com", "key", "secret", "12345678", "01", "SOXL", "AMS"
    );

    @BeforeEach
    void setUp() {
        adapter = new KisTokenAdapter(kisRestTemplate, TEST_PROPS, kisTokenCachePort);
    }

    @Test
    @DisplayName("캐시에 유효 토큰 있으면 KIS API 호출 없이 반환")
    void getToken_whenCacheHit_returnsCachedToken() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.of("cached-token"));

        String result = adapter.getToken();

        assertThat(result).isEqualTo("cached-token");
        verifyNoInteractions(kisRestTemplate);
    }

    @Test
    @DisplayName("캐시 미스 시 KIS API 호출하여 신규 토큰 반환")
    void getToken_whenCacheMiss_returnsNewToken() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.empty());
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisTokenAdapter.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new KisTokenAdapter.TokenResponse("new-token", "2099-12-31 23:59:59")));

        String result = adapter.getToken();

        assertThat(result).isEqualTo("new-token");
    }

    @Test
    @DisplayName("캐시 미스 시 발급 토큰을 saveToken으로 캐시에 저장")
    void getToken_whenCacheMiss_savesTokenToCache() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.empty());
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisTokenAdapter.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new KisTokenAdapter.TokenResponse("new-token", "2099-12-31 23:59:59")));

        adapter.getToken();

        ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(kisTokenCachePort).saveToken(eq("new-token"), expiresCaptor.capture());
        // KIS 응답은 KST(+09:00)이므로 offset이 +09:00이어야 함
        assertThat(expiresCaptor.getValue().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }

    @Test
    @DisplayName("캐시 만료 1분 전 임박 토큰은 재발급 — findValidToken에 now+1분 전달")
    void getToken_uses1MinBuffer_forCacheCheck() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.empty());
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisTokenAdapter.TokenResponse.class)))
                .thenReturn(ResponseEntity.ok(new KisTokenAdapter.TokenResponse("new-token", "2099-12-31 23:59:59")));

        adapter.getToken();

        ArgumentCaptor<OffsetDateTime> thresholdCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(kisTokenCachePort).findValidToken(thresholdCaptor.capture());
        // 전달된 threshold가 현재 시각보다 최소 59초 이상 미래여야 함 (1분 버퍼)
        assertThat(thresholdCaptor.getValue()).isAfter(OffsetDateTime.now().plusSeconds(59));
    }

    @Test
    @DisplayName("parseExpiry: KST 문자열을 +09:00 OffsetDateTime으로 파싱")
    void parseExpiry_parsesKstStringCorrectly() {
        OffsetDateTime result = adapter.parseExpiry("2024-06-16 05:17:02");

        assertThat(result.getYear()).isEqualTo(2024);
        assertThat(result.getMonthValue()).isEqualTo(6);
        assertThat(result.getDayOfMonth()).isEqualTo(16);
        assertThat(result.getHour()).isEqualTo(5);
        assertThat(result.getOffset().getTotalSeconds()).isEqualTo(9 * 3600); // +09:00
    }
}
