package com.kista.adapter.out.kis;

import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisHttpClient 401 재시도·예외 래핑 검증")
class KisHttpClientTest {

    @Mock KisAuthApi kisAuthApi; // 구체 클래스 직접 mock

    RestClient.Builder restClientBuilder;
    MockRestServiceServer server;

    private static final String BASE_URL = "http://kis.test";
    private static final String TR_ID = "CTRP6504R";
    private static final String PATH = "/uapi/test";

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", null,
            Account.Broker.KIS, null
    );

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private KisHttpClient newClient() {
        return new KisHttpClient(restClientBuilder.build(), kisAuthApi, BASE_URL);
    }

    // KIS EGW00201(초당 거래건수 초과) 500 응답 바디
    private static final String RATE_LIMIT_BODY =
            "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\",\"message\":\"EGW00201\"}";

    private void expectGet(HttpStatus status, String body) {
        server.expect(requestTo(startsWithPath()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(status.is2xxSuccessful()
                        ? withSuccess(body, MediaType.TEXT_PLAIN)
                        : withServerErrorBody(status, body));
    }

    private org.springframework.test.web.client.ResponseCreator withServerErrorBody(HttpStatus status, String body) {
        return status == HttpStatus.UNAUTHORIZED ? withUnauthorizedRequest().body(body) : withServerError().body(body);
    }

    private String startsWithPath() {
        return BASE_URL + PATH + "?CANO=74420614&ACNT_PRDT_CD=01";
    }

    @Test
    @DisplayName("401 1회 → 토큰 복구 후 재시도 성공")
    void retriesOnceAfter401_thenSucceeds() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("rejected-token");
        when(kisAuthApi.recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "rejected-token"))
                .thenReturn(new TokenCoordinator.RecoveredToken("fresh-token", true));
        expectGet(HttpStatus.UNAUTHORIZED, "");
        expectGet(HttpStatus.OK, "OK");

        String result = newClient().tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});

        assertThat(result).isEqualTo("OK");
        // 401 감지 → 복구(무효화+재발급) 1회, 복구된 토큰을 바로 재시도에 사용 — getToken 재호출 없음
        verify(kisAuthApi).recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "rejected-token");
        verify(kisAuthApi, org.mockito.Mockito.times(1)).getToken(any(), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("401 2회(재시도도 401) → KisApiException")
    void throwsKisApiException_when401Twice() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        when(kisAuthApi.recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "token"))
                .thenReturn(new TokenCoordinator.RecoveredToken("token", true));
        expectGet(HttpStatus.UNAUTHORIZED, "");
        expectGet(HttpStatus.UNAUTHORIZED, "");

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("재시도 실패");

        verify(kisAuthApi).recoverToken(ACCOUNT.id(), ACCOUNT.appKey(), ACCOUNT.secretKey(), "token");
        server.verify();
    }

    @Test
    @DisplayName("RestClientException(비 401) → KisApiException 래핑, 재시도 없음")
    void wrapsRestClientException_withoutRetry() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        server.expect(requestTo(startsWithPath()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> { throw new IOException("connection refused"); });

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("요청 실패");

        // 비 401은 복구·재시도 없음
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("HTTP 4xx(비 401) → KisApiException 래핑, 재시도 없음")
    void wrapsNon401StatusException_withoutRetry() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        server.expect(requestTo(startsWithPath()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withBadRequest().body(""));

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("GET: EGW00201 1회 → 백오프 후 재시도 성공")
    // 백오프 상수(RATE_LIMIT_BACKOFF_BASE_MILLIS=1000ms)만큼 실제로 대기하므로 이 테스트는 ~1초가 걸린다.
    // 스위트 전체가 느려지는 게 문제가 되면 리뷰 시점에 판단할 것 — 임의로 상수를 낮추지 않았음
    void retriesOnceAfterRateLimit_thenSucceeds() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        expectGet(HttpStatus.INTERNAL_SERVER_ERROR, RATE_LIMIT_BODY);
        expectGet(HttpStatus.OK, "OK");

        String result = newClient().tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});

        assertThat(result).isEqualTo("OK");
        // 토큰 문제가 아니므로 재조회 없이 같은 토큰으로 재시도
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("GET: EGW00201이 MAX_RATE_LIMIT_RETRIES+1회 연속 → KisApiException")
    void throwsKisApiException_whenRateLimitedBeyondMaxRetries() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        expectGet(HttpStatus.INTERNAL_SERVER_ERROR, RATE_LIMIT_BODY);
        expectGet(HttpStatus.INTERNAL_SERVER_ERROR, RATE_LIMIT_BODY);
        expectGet(HttpStatus.INTERNAL_SERVER_ERROR, RATE_LIMIT_BODY);

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        // MAX_RATE_LIMIT_RETRIES=2 → attempt 0,1은 재시도, attempt 2에서 최종 실패 → 총 3회 호출
        server.verify();
        verify(kisAuthApi, never()).recoverToken(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("POST(주문 접수): EGW00201 → 재시도 없이 즉시 KisApiException")
    void doesNotRetryRateLimit_onPost() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        server.expect(requestTo(BASE_URL + PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().body(RATE_LIMIT_BODY));

        KisHttpClient client = newClient();
        assertThatThrownBy(() -> client.post(TR_ID, PATH, ACCOUNT, "{}", String.class))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("KIS API 오류");

        // retryOnRateLimit=false — 접수/취소는 재시도하지 않음(사전 호출 간격 게이트로 별도 대응)
        server.verify();
    }

    @Test
    @DisplayName("같은 appKey 연속 호출 2건 → 최소 간격(MIN_CALL_INTERVAL_MILLIS) 이상 벌어짐")
    // 실제로 최소 간격만큼 대기하므로 이 테스트는 ~350ms가 걸린다.
    void spacesConsecutiveCallsForSameAppKey() {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");
        expectGet(HttpStatus.OK, "OK");
        expectGet(HttpStatus.OK, "OK");

        KisHttpClient client = newClient();
        long start = System.nanoTime();
        client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});
        client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {});
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(340); // 지터 없는 고정 간격, 스케쥴링 오차만 소폭 허용
        server.verify();
    }

    @Test
    @DisplayName("같은 appKey 대기열이 대기 상한(MAX_QUEUE_WAIT_MILLIS)을 넘으면 대기 없이 즉시 KisApiException")
    void throwsImmediately_whenQueueWaitExceedsCap() throws Exception {
        when(kisAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token");

        KisHttpClient client = newClient();
        // 실제로 20초를 기다리지 않기 위해 다음 슬롯이 이미 25초 뒤로 예약된 것처럼 내부 상태를 직접 주입
        java.lang.reflect.Field field = KisHttpClient.class.getDeclaredField("nextSlotByAppKey");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var slots = (java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>) field.get(client);
        java.util.concurrent.atomic.AtomicLong slot = new java.util.concurrent.atomic.AtomicLong(System.nanoTime() + 25_000_000_000L);
        slots.put(ACCOUNT.appKey(), slot);
        long slotBefore = slot.get();

        assertThatThrownBy(() -> client.tradingGet(TR_ID, PATH, ACCOUNT, String.class, p -> {}))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("대기열 과다");

        // 대기하지 않고 즉시 실패해야 하므로 브로커 호출 자체가 발생하지 않음 — 등록된 expectation이 없어 요청 발생 시 서버가 즉시 실패
        server.verify();
        // 회귀 방지: 거부된 호출이 슬롯을 커밋해 대기열을 더 미래로 밀어버리면 안 됨(반복 거부 시 무한 누적 버그 재발 감지)
        assertThat(slot.get()).isEqualTo(slotBefore);
    }
}
