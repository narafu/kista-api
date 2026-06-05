package com.kista.adapter.out.kis;

import com.kista.domain.port.out.KisTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisConnectionTestAdapter 단위 테스트")
class KisConnectionTestAdapterTest {

    @Mock RestTemplate kisRestTemplate;
    @Mock KisTokenCachePort kisTokenCachePort;

    KisConnectionTestAdapter adapter;

    private static final String BASE_URL = "https://openapi.koreainvestment.com:9443";
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        adapter = new KisConnectionTestAdapter(kisRestTemplate, kisTokenCachePort, BASE_URL);
    }

    @Test
    @DisplayName("KIS OAuth 2xx 응답 시 true 반환 — accountId null이면 캐시 저장 생략")
    void test_whenKisReturns2xx_returnsTrueWithoutCaching() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisConnectionTestAdapter.TokenCheckResponse.class)))
                .thenReturn(ResponseEntity.ok(new KisConnectionTestAdapter.TokenCheckResponse("tok", "2099-12-31 23:59:59")));

        assertThat(adapter.test("appKey", "appSecret", null)).isTrue();
        verifyNoInteractions(kisTokenCachePort);
    }

    @Test
    @DisplayName("accountId 있고 캐시 미스 시 KIS 호출 후 토큰 캐시 저장")
    void test_whenAccountIdPresentAndCacheMiss_savesTokenToCache() {
        when(kisTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.empty());
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisConnectionTestAdapter.TokenCheckResponse.class)))
                .thenReturn(ResponseEntity.ok(new KisConnectionTestAdapter.TokenCheckResponse("tok", "2099-12-31 23:59:59")));

        assertThat(adapter.test("appKey", "appSecret", ACCOUNT_ID)).isTrue();
        verify(kisTokenCachePort).saveToken(eq(ACCOUNT_ID), eq("tok"), any());
    }

    @Test
    @DisplayName("accountId 있고 캐시 히트 시 KIS 호출 없이 true 반환")
    void test_whenAccountIdPresentAndCacheHit_returnsTrueWithoutKisCall() {
        when(kisTokenCachePort.findValidToken(eq(ACCOUNT_ID), any())).thenReturn(Optional.of("cached-token"));

        assertThat(adapter.test("appKey", "appSecret", ACCOUNT_ID)).isTrue();
        verifyNoInteractions(kisRestTemplate);
        verify(kisTokenCachePort, never()).saveToken(any(), any(), any());
    }

    @Test
    @DisplayName("KIS OAuth 4xx 응답 시 false 반환")
    void test_whenKisReturns4xx_returnsFalse() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(KisConnectionTestAdapter.TokenCheckResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, new byte[]{}, null));

        assertThat(adapter.test("badKey", "badSecret", null)).isFalse();
    }
}
