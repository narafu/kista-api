package com.kista.adapter.out.kis;

import com.kista.domain.port.out.KisTokenCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.time.OffsetDateTime;
import java.util.Map;
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
    KisHttpClient kisHttpClient;

    @Mock
    KisTokenCachePort kisTokenCachePort;

    @InjectMocks
    KisTokenAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://api.test.com", "key", "secret", "12345678", "01", "SOXL", "AMS"
    );

    @Test
    @DisplayName("캐시에 유효 토큰 있으면 KIS API 호출 없이 반환")
    void getToken_whenCacheHit_returnsCachedToken() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.of("cached-token"));

        String result = adapter.getToken();

        assertThat(result).isEqualTo("cached-token");
        verifyNoInteractions(kisHttpClient);
    }

    @Test
    @DisplayName("캐시 미스 시 KIS API 호출하여 신규 토큰 반환")
    void getToken_whenCacheMiss_returnsNewToken() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.empty());
        when(kisHttpClient.props()).thenReturn(TEST_PROPS);
        when(kisHttpClient.post(anyString(), any(HttpHeaders.class), any(Map.class), any()))
                .thenReturn(new KisTokenAdapter.TokenResponse("new-token", "2099-12-31 23:59:59"));

        String result = adapter.getToken();

        assertThat(result).isEqualTo("new-token");
    }

    @Test
    @DisplayName("캐시 미스 시 발급 토큰을 saveToken으로 캐시에 저장")
    void getToken_whenCacheMiss_savesTokenToCache() {
        when(kisTokenCachePort.findValidToken(any())).thenReturn(Optional.empty());
        when(kisHttpClient.props()).thenReturn(TEST_PROPS);
        when(kisHttpClient.post(anyString(), any(HttpHeaders.class), any(Map.class), any()))
                .thenReturn(new KisTokenAdapter.TokenResponse("new-token", "2099-12-31 23:59:59"));

        adapter.getToken();

        ArgumentCaptor<OffsetDateTime> expiresCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(kisTokenCachePort).saveToken(eq("new-token"), expiresCaptor.capture());
        // KIS 응답은 KST(+09:00)이므로 offset이 +09:00이어야 함
        assertThat(expiresCaptor.getValue().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
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
