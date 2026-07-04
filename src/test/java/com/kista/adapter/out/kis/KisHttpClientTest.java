package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisHttpClient 401 재시도·예외 래핑 검증")
class KisHttpClientTest {

    @Mock RestTemplate kisRestTemplate;
    @Mock KisAuthApi kisAuthApi; // 구체 클래스 직접 mock

    private static final String TR_ID = "CTRP6504R";
    private static final String PATH = "/uapi/test";

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", null,
            Account.Broker.KIS, null
    );

    private KisHttpClient newClient() {
        return new KisHttpClient(kisRestTemplate, kisAuthApi, "http://kis.test");
    }

    // KIS 401 응답 생성 헬퍼
    private HttpClientErrorException unauthorized() {
        return HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    @DisplayName("401 1회 → 토큰 무효화 후 재발급·재시도 성공")
    void retriesOnceAfter401_thenSucceeds() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});

        assertThat(result).isEqualTo("OK");
        // 401 감지 → 토큰 무효화 1회
        verify(kisAuthApi).invalidateToken(ACCOUNT.id());
        // 최초 + 재시도 = getToken 2회, exchange 2회
        verify(kisAuthApi, times(2)).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(kisRestTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("401 2회(재시도도 401) → KisApiException")
    void throwsKisApiException_when401Twice() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized());

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("재시도 실패");

        verify(kisAuthApi).invalidateToken(ACCOUNT.id());
    }

    @Test
    @DisplayName("RestClientException(비 401) → KisApiException 래핑, 재시도 없음")
    void wrapsRestClientException_withoutRetry() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("요청 실패");

        // 비 401은 무효화·재시도 없음
        verify(kisAuthApi, never()).invalidateToken(any());
        verify(kisRestTemplate, times(1)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("HTTP 4xx(비 401) → KisApiException 래핑, 재시도 없음")
    void wrapsNon401StatusException_withoutRetry() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null));

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        verify(kisAuthApi, never()).invalidateToken(any());
    }
}
