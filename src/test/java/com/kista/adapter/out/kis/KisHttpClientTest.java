package com.kista.adapter.out.kis;

import com.kista.adapter.out.broker.TokenCoordinator;
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
import org.springframework.web.client.HttpServerErrorException;
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

    // KIS EGW00201(초당 거래건수 초과) 500 응답 생성 헬퍼
    private HttpServerErrorException rateLimited() {
        byte[] body = "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\",\"message\":\"EGW00201\"}"
                .getBytes();
        return HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY, body, null);
    }

    @Test
    @DisplayName("401 1회 → 토큰 복구 후 재시도 성공")
    void retriesOnceAfter401_thenSucceeds() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("rejected-token");
        when(kisAuthApi.recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "rejected-token"))
                .thenReturn(new TokenCoordinator.RecoveredToken("fresh-token", true));
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});

        assertThat(result).isEqualTo("OK");
        // 401 감지 → 복구(무효화+재발급) 1회, 복구된 토큰을 바로 재시도에 사용 — getToken 재호출 없음
        verify(kisAuthApi).recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "rejected-token");
        verify(kisAuthApi, times(1)).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(kisRestTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("401 2회(재시도도 401) → KisApiException")
    void throwsKisApiException_when401Twice() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisAuthApi.recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "token"))
                .thenReturn(new TokenCoordinator.RecoveredToken("token", true));
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized());

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("재시도 실패");

        verify(kisAuthApi).recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "token");
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

        // 비 401은 복구·재시도 없음
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
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

        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("GET: EGW00201 1회 → 백오프 후 재시도 성공")
    // 백오프 상수(RATE_LIMIT_BACKOFF_BASE_MILLIS=1000ms)만큼 실제로 대기하므로 이 테스트는 ~1초가 걸린다.
    // 스위트 전체가 느려지는 게 문제가 되면 리뷰 시점에 판단할 것 — 임의로 상수를 낮추지 않았음
    void retriesOnceAfterRateLimit_thenSucceeds() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(rateLimited())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});

        assertThat(result).isEqualTo("OK");
        // 토큰 문제가 아니므로 재조회 없이 같은 토큰으로 재시도
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
        verify(kisRestTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("GET: EGW00201이 MAX_RATE_LIMIT_RETRIES+1회 연속 → KisApiException")
    void throwsKisApiException_whenRateLimitedBeyondMaxRetries() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(rateLimited(), rateLimited(), rateLimited());

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        // MAX_RATE_LIMIT_RETRIES=2 → attempt 0,1은 재시도, attempt 2에서 최종 실패 → 총 3회 호출
        verify(kisRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST(주문 접수): EGW00201 → 재시도 없이 즉시 KisApiException")
    void doesNotRetryRateLimit_onPost() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(rateLimited());

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.post(TR_ID, PATH, ACCOUNT, "{}", String.class))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        // retryOnRateLimit=false — 접수/취소는 재시도하지 않음(사전 페이싱으로 별도 대응)
        verify(kisRestTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}
