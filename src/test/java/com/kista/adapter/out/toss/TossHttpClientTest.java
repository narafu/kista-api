package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossHttpClient 401 재시도·백오프 검증")
class TossHttpClientTest {

    @Mock RestTemplate tossRestTemplate;
    @Mock TossAuthApi tossAuthApi; // 구체 클래스 직접 mock

    private static final String PATH = "/api/v1/holdings";

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "12345678901", "cid", "csecret", "1",
            Account.Broker.TOSS, null
    );

    private TossHttpClient newClient() {
        return new TossHttpClient(tossRestTemplate, tossAuthApi, "http://toss.test");
    }

    private HttpClientErrorException unauthorized() {
        return HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    @DisplayName("신규 토큰 전파가 지연되면 같은 신규 토큰으로 재시도")
    @SuppressWarnings("unchecked")
    void retriesSameFreshToken_whenPropagationIsDelayed() {
        when(tossAuthApi.getToken(any(), anyString(), anyString()))
                .thenReturn("token-0", "token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-0");
        verify(tossAuthApi, never()).invalidateToken(ACCOUNT.id(), "token-1");
        verify(tossAuthApi, times(2)).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
        ArgumentCaptor<HttpEntity<?>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                requestCaptor.capture(), any(ParameterizedTypeReference.class));
        assertThat(requestCaptor.getAllValues())
                .extracting(request -> request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer token-0", "Bearer token-1", "Bearer token-1");
    }

    @Test
    @DisplayName("재시도 한도 후에도 신규 토큰은 무효화하지 않음")
    void throwsAfterRetryLimit_withoutInvalidatingFreshToken() {
        when(tossAuthApi.getToken(any(), anyString(), anyString()))
                .thenReturn("token-0", "token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).invalidateToken(ACCOUNT.id(), "token-0");
        verify(tossAuthApi, never()).invalidateToken(ACCOUNT.id(), "token-1");
        verify(tossAuthApi, times(2)).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("공통 API(getCommon) 401도 최대 2회까지 백오프 재시도 후 성공")
    void getCommon_retriesTwiceAfter401_thenSucceeds() {
        when(tossAuthApi.getAdminToken())
                .thenReturn("admin-token-0", "admin-token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().getCommon(PATH, new LinkedMultiValueMap<>(), String.class);

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).invalidateAdminToken("admin-token-0");
        verify(tossAuthApi, never()).invalidateAdminToken("admin-token-1");
        verify(tossAuthApi, times(2)).getAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("공통 API 401이 세 번(최초+2차 재시도 모두) 발생하면 TossApiException")
    void getCommon_throwsTossApiException_when401Persists() {
        when(tossAuthApi.getAdminToken())
                .thenReturn("admin-token-0", "admin-token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).invalidateAdminToken("admin-token-0");
        verify(tossAuthApi, never()).invalidateAdminToken("admin-token-1");
        verify(tossAuthApi, times(2)).getAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }
}
