package com.kista.adapter.out.toss;

import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossHttpClient 401 재시도·백오프 검증")
class TossHttpClientTest {

    @Mock TossAuthApi tossAuthApi; // 구체 클래스 직접 mock

    private static final String BASE_URL = "http://toss.test";
    private static final String PATH = "/api/v1/holdings";
    private static final String URL = BASE_URL + PATH;

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "12345678901", "cid", "csecret", "1",
            Account.Broker.TOSS, null
    );

    RestClient.Builder restClientBuilder;
    MockRestServiceServer server;

    private void setUpServer() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private TossHttpClient newClient() {
        setUpServer();
        return new TossHttpClient(restClientBuilder.build(), tossAuthApi, BASE_URL);
    }

    private void expectGet(String bearerToken, HttpStatus status, String body) {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + bearerToken))
                .andRespond(status.is2xxSuccessful()
                        ? withSuccess(body, MediaType.TEXT_PLAIN)
                        : withUnauthorizedRequest().body(body));
    }

    @Test
    @DisplayName("신규 토큰 전파가 지연되면 매 401마다 복구를 재조회해 같은 신규 토큰으로 재시도")
    void retriesSameFreshToken_whenPropagationIsDelayed() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-1", true));
        // 두 번째 401도 다른 값(token-1)이라 forceReissue=false로 재조회하며, 코디네이터가 자체 지문 보호로
        // 같은 token-1을 다시 반환한다(전파가 아직 안 끝난 것으로 판단, 대기 유지)
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-1"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-1", true));
        expectGet("token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-1", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-1", HttpStatus.OK, "OK");

        String result = client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0", false);
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-1", false);
        verify(tossAuthApi, never()).recoverToken(eq(ACCOUNT.id()), eq("cid"), eq("csecret"), anyString(), eq(true));
        verify(tossAuthApi).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("같은 토큰이 두 번째로 거절되면 지문 보호를 건너뛰고 강제 재발급(forceReissue)으로 승격")
    void escalatesToForceReissue_whenSameTokenRejectedTwiceInARow() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        // 1차 401: 지문 보호 재사용으로 같은 token-0을 다시 받음(freshlyIssued=true, 전파 대기)
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-0", true));
        // 2차 401(같은 token-0 재거절) → forceReissue=true로 승격, 코디네이터가 실제 재발급한 token-1 반환
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0"), eq(true)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-1", true));
        expectGet("token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-1", HttpStatus.OK, "OK");

        String result = client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0", false);
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0", true);
        server.verify();
    }

    @Test
    @DisplayName("재시도 한도(MAX=3) 도달 시 매 401마다 해당 토큰의 복구를 시도하되 서로 다른 토큰이면 강제 재발급으로 승격하지 않음")
    void throwsAfterRetryLimit_recoveringEachDistinctRejectedTokenOnce() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        // 세 번의 복구가 매번 서로 다른 값을 반환 — 어느 것도 직전과 동일하지 않으므로 forceReissue는 한 번도 true가 되지 않음
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-1", true));
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-1"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-2", true));
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-2"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-3", true));
        expectGet("token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-1", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-2", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-3", HttpStatus.UNAUTHORIZED, "");

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0", false);
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-1", false);
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-2", false);
        // 마지막(4번째) 시도는 attempt>=MAX_RETRY_ATTEMPTS로 즉시 실패해 token-3에 대한 복구는 시도되지 않음
        verify(tossAuthApi, never()).recoverToken(eq(ACCOUNT.id()), eq("cid"), eq("csecret"), eq("token-3"), anyBoolean());
        verify(tossAuthApi, never()).recoverToken(eq(ACCOUNT.id()), eq("cid"), eq("csecret"), anyString(), eq(true));
        verify(tossAuthApi).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        server.verify();
    }

    @Test
    @DisplayName("409 CONFLICT(already-canceled) 응답은 TossApiException.isAlreadyCanceledConflict()=true로 전달")
    void get_409AlreadyCanceled_setsAlreadyCanceledConflictFlag() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.CONFLICT).body(
                        "{\"error\":{\"code\":\"already-canceled\",\"message\":\"취소된 주문입니다.\"}}"));

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOfSatisfying(TossApiException.class,
                        tae -> assertThat(tae.isAlreadyCanceledConflict()).isTrue());
        server.verify();
    }

    @Test
    @DisplayName("409 CONFLICT(already-filled) 응답은 already-canceled로 오판정되지 않는다")
    void get_409AlreadyFilled_doesNotSetAlreadyCanceledConflictFlag() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.CONFLICT).body(
                        "{\"error\":{\"code\":\"already-filled\",\"message\":\"체결 완료된 주문입니다.\"}}"));

        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOfSatisfying(TossApiException.class, tae -> {
                    assertThat(tae.isAlreadyFilledConflict()).isTrue();
                    assertThat(tae.isAlreadyCanceledConflict()).isFalse();
                });
        server.verify();
    }

    @Test
    @DisplayName("공통 API(getCommon) 401도 매번 복구를 재조회하며 백오프 재시도 후 성공")
    void getCommon_retriesTwiceAfter401_thenSucceeds() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getAdminToken()).thenReturn("admin-token-0");
        when(tossAuthApi.recoverAdminToken("admin-token-0", false))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", true));
        // 두 번째 401도 다른 값(admin-token-1)이라 forceReissue=false로 계속 복구를 재조회
        when(tossAuthApi.recoverAdminToken("admin-token-1", false))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", true));
        expectGet("admin-token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("admin-token-1", HttpStatus.UNAUTHORIZED, "");
        expectGet("admin-token-1", HttpStatus.OK, "OK");

        String result = client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class);

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).recoverAdminToken("admin-token-0", false);
        verify(tossAuthApi).recoverAdminToken("admin-token-1", false);
        verify(tossAuthApi, never()).recoverAdminToken(anyString(), eq(true));
        verify(tossAuthApi).getAdminToken();
        server.verify();
    }

    @Test
    @DisplayName("공통 API 401이 재시도 한도(MAX=3) 내내 발생하면 TossApiException")
    void getCommon_throwsTossApiException_when401Persists() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getAdminToken()).thenReturn("admin-token-0");
        // 세 번의 복구가 매번 서로 다른 값을 반환 — 어느 것도 직전과 동일하지 않으므로 forceReissue는 한 번도 true가 되지 않음
        when(tossAuthApi.recoverAdminToken("admin-token-0", false))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-1", true));
        when(tossAuthApi.recoverAdminToken("admin-token-1", false))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-2", true));
        when(tossAuthApi.recoverAdminToken("admin-token-2", false))
                .thenReturn(new TokenCoordinator.RecoveredToken("admin-token-3", true));
        expectGet("admin-token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("admin-token-1", HttpStatus.UNAUTHORIZED, "");
        expectGet("admin-token-2", HttpStatus.UNAUTHORIZED, "");
        expectGet("admin-token-3", HttpStatus.UNAUTHORIZED, "");

        assertThatThrownBy(() -> client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).recoverAdminToken("admin-token-0", false);
        verify(tossAuthApi).recoverAdminToken("admin-token-1", false);
        verify(tossAuthApi).recoverAdminToken("admin-token-2", false);
        // 마지막(4번째) 시도는 attempt>=MAX_RETRY_ATTEMPTS로 즉시 실패해 admin-token-3에 대한 복구는 시도되지 않음
        verify(tossAuthApi, never()).recoverAdminToken(eq("admin-token-3"), anyBoolean());
        verify(tossAuthApi, never()).recoverAdminToken(anyString(), eq(true));
        verify(tossAuthApi).getAdminToken();
        server.verify();
    }

    @Test
    @DisplayName("복구된 토큰이 이미 다른 canonical 토큰의 재사용(freshlyIssued=false)이면 백오프 대기를 생략")
    void skipsBackoff_whenRecoveredTokenIsCheapReuse_notFreshlyIssued() {
        TossHttpClient client = newClient();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        // freshlyIssued=false — 이미 다른 인스턴스가 저장해 둔 canonical 토큰을 Redis 읽기만으로 재사용
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0"), eq(false)))
                .thenReturn(new TokenCoordinator.RecoveredToken("token-1", false));
        expectGet("token-0", HttpStatus.UNAUTHORIZED, "");
        expectGet("token-1", HttpStatus.OK, "OK");

        long start = System.nanoTime();
        String result = client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result).isEqualTo("OK");
        // sleepBackoff(0)은 300ms — freshlyIssued=false면 생략되므로 훨씬 짧은 시간 안에 재시도가 끝나야 한다
        assertThat(elapsedMillis).isLessThan(250);
        server.verify();
    }

    // ── 동시성 테스트 — MockRestServiceServer는 스레드-안전이 보장되지 않아 실제 요청 순서를 스텁으로
    // 강제할 수 없으므로, 스레드별로 응답을 분기하는 커스텀 ClientHttpRequestFactory를 직접 구현한다.
    // 코디네이터 자체의 generation fencing/lease 로직은 TossDistributedTokenCoordinatorTest가 별도 검증하므로
    // 여기서는 TossHttpClient의 재시도 루프가 실제 코디네이터·동시 요청과 맞물려도 올바르게 동작하는지만 확인한다.

    private RestClient sharedRestClient(String oauthTokenPrefix, AtomicInteger oauthFetchCount,
                                         BiFunction<String, String, ClientHttpResponse> getResponder) {
        ClientHttpRequestFactory factory = (uri, httpMethod) -> new FakeRequest(uri, httpMethod) {
            @Override
            public ClientHttpResponse execute() {
                if (httpMethod == HttpMethod.POST) {
                    String token = oauthTokenPrefix + oauthFetchCount.incrementAndGet();
                    String json = "{\"access_token\":\"" + token + "\",\"expires_in\":86400}";
                    return jsonResponse(HttpStatus.OK, json);
                }
                String authorization = getHeaders().getFirst("Authorization");
                return getResponder.apply(Thread.currentThread().getName(), authorization);
            }
        };
        return RestClient.builder().requestFactory(factory).build();
    }

    private static ClientHttpResponse jsonResponse(HttpStatus status, String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }

    private static ClientHttpResponse textResponse(HttpStatus status, String body) {
        MockClientHttpResponse response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.TEXT_PLAIN);
        return response;
    }

    // 최소 골격만 구현하는 ClientHttpRequest — execute()는 하위 클래스가 오버라이드
    private abstract static class FakeRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        FakeRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();

        @Override public org.springframework.http.HttpHeaders getHeaders() { return headers; }
        @Override public OutputStream getBody() { return body; }
        @Override public HttpMethod getMethod() { return method; }
        @Override public URI getURI() { return uri; }
        @Override public java.util.Map<String, Object> getAttributes() { return attributes; }
    }

    @Test
    @DisplayName("늦게 시작한 계좌 요청의 token-1 401은 최근 발급 세대를 재사용")
    void staggeredAccountRequests_reuseRecentlyIssuedTokenGeneration() throws InterruptedException {
        CountDownLatch token1Stored = new CountDownLatch(1);
        TossDistributedTokenCoordinator tokenCoordinator = mockAccountCoordinator("token-0", token1Stored);
        AtomicInteger oauthFetchCount = new AtomicInteger();
        CountDownLatch requestCRejectedToken1 = new CountDownLatch(1);
        AtomicInteger requestCToken1Calls = new AtomicInteger();

        RestClient sharedClient = sharedRestClient("token-", oauthFetchCount, (threadName, authorization) -> {
            if (threadName.equals("account-request-a") && "Bearer token-0".equals(authorization)) {
                return textResponse(HttpStatus.UNAUTHORIZED, "");
            }
            if (threadName.equals("account-request-a") && "Bearer token-1".equals(authorization)) {
                await(requestCRejectedToken1);
                return textResponse(HttpStatus.OK, "A:token-1");
            }
            if (threadName.equals("account-request-c") && "Bearer token-1".equals(authorization)
                    && requestCToken1Calls.incrementAndGet() == 1) {
                requestCRejectedToken1.countDown();
                return textResponse(HttpStatus.UNAUTHORIZED, "");
            }
            return textResponse(HttpStatus.OK, "C:" + authorization.substring("Bearer ".length()));
        });
        TossAuthApi realAuthApi = new TossAuthApi(sharedClient, tokenCoordinator,
                BASE_URL, "admin-id", "admin-secret");
        TossHttpClient client = new TossHttpClient(sharedClient, realAuthApi, BASE_URL);

        AtomicReference<String> requestAResult = new AtomicReference<>();
        AtomicReference<String> requestCResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread requestA = startRequest("account-request-a", failure,
                () -> requestAResult.set(client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                        new ParameterizedTypeReference<String>() {})));
        Thread requestC = startRequest("account-request-c", failure, () -> {
            await(token1Stored);
            requestCResult.set(client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                    new ParameterizedTypeReference<String>() {}));
        });

        join(requestA);
        join(requestC);

        assertThat(failure.get()).isNull();
        assertThat(requestAResult.get()).isEqualTo("A:token-1");
        assertThat(requestCResult.get()).isEqualTo("C:token-1");
        assertThat(oauthFetchCount.get()).isOne();
    }

    @Test
    @DisplayName("늦게 시작한 관리자 요청의 token-1 401은 최근 발급 세대를 재사용")
    void staggeredAdminRequests_reuseRecentlyIssuedTokenGeneration() throws InterruptedException {
        CountDownLatch token1Issued = new CountDownLatch(1);
        TossDistributedTokenCoordinator tokenCoordinator = mockAdminCoordinator("admin-token-0", token1Issued);
        AtomicInteger oauthFetchCount = new AtomicInteger();
        CountDownLatch requestCRejectedToken1 = new CountDownLatch(1);
        AtomicInteger requestCToken1Calls = new AtomicInteger();

        RestClient sharedClient = sharedRestClient("admin-token-", oauthFetchCount, (threadName, authorization) -> {
            if (threadName.equals("admin-request-a") && "Bearer admin-token-0".equals(authorization)) {
                return textResponse(HttpStatus.UNAUTHORIZED, "");
            }
            if (threadName.equals("admin-request-a") && "Bearer admin-token-1".equals(authorization)) {
                await(requestCRejectedToken1);
                return textResponse(HttpStatus.OK, "A:admin-token-1");
            }
            if (threadName.equals("admin-request-c") && "Bearer admin-token-1".equals(authorization)
                    && requestCToken1Calls.incrementAndGet() == 1) {
                requestCRejectedToken1.countDown();
                return textResponse(HttpStatus.UNAUTHORIZED, "");
            }
            return textResponse(HttpStatus.OK, "C:" + authorization.substring("Bearer ".length()));
        });
        TossAuthApi realAuthApi = new TossAuthApi(sharedClient, tokenCoordinator,
                BASE_URL, "admin-id", "admin-secret");
        TossHttpClient client = new TossHttpClient(sharedClient, realAuthApi, BASE_URL);

        AtomicReference<String> requestAResult = new AtomicReference<>();
        AtomicReference<String> requestCResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread requestA = startRequest("admin-request-a", failure,
                () -> requestAResult.set(client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class)));
        Thread requestC = startRequest("admin-request-c", failure, () -> {
            await(token1Issued);
            requestCResult.set(client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class));
        });

        join(requestA);
        join(requestC);

        assertThat(failure.get()).isNull();
        assertThat(requestAResult.get()).isEqualTo("A:admin-token-1");
        assertThat(requestCResult.get()).isEqualTo("C:admin-token-1");
        assertThat(oauthFetchCount.get()).isOne();
    }

    private Thread startRequest(String name, AtomicReference<Throwable> failure, Runnable request) {
        return Thread.ofVirtual().name(name).start(() -> {
            try {
                request.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertThat(thread.isAlive()).isFalse();
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruptedException);
        }
    }

    private TossDistributedTokenCoordinator mockAccountCoordinator(
            String initialToken, CountDownLatch tokenStored) {
        TossDistributedTokenCoordinator coordinator = org.mockito.Mockito.mock(
                TossDistributedTokenCoordinator.class);
        AtomicReference<String> currentToken = new AtomicReference<>(initialToken);
        when(coordinator.obtain(any(), any())).thenAnswer(invocation -> currentToken.get());
        when(coordinator.recover(any(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String rejectedToken = invocation.getArgument(1);
                    String current = currentToken.get();
                    if (!current.equals(rejectedToken)) {
                        // 이미 다른 인스턴스가 저장해 둔 canonical 토큰 재사용 — 전파 대기 불필요
                        return new TokenCoordinator.RecoveredToken(current, false);
                    }
                    TokenCoordinator.TokenIssuer issuer = invocation.getArgument(2);
                    if ("token-1".equals(rejectedToken)) {
                        // 최근 발급 지문 보호 구간 내 재사용 — 전파 대기 필요
                        return new TokenCoordinator.RecoveredToken(rejectedToken, true);
                    }
                    String issued = issuer.issue().accessToken();
                    currentToken.set(issued);
                    tokenStored.countDown();
                    // 실제 신규 발급 — 전파 대기 필요
                    return new TokenCoordinator.RecoveredToken(issued, true);
                });
        return coordinator;
    }

    private TossDistributedTokenCoordinator mockAdminCoordinator(
            String initialToken, CountDownLatch tokenStored) {
        TossDistributedTokenCoordinator coordinator = org.mockito.Mockito.mock(
                TossDistributedTokenCoordinator.class);
        AtomicReference<String> currentToken = new AtomicReference<>(initialToken);
        when(coordinator.getAdminToken(any())).thenAnswer(invocation -> currentToken.get());
        when(coordinator.recoverAdminToken(anyString(), any(), anyBoolean())).thenAnswer(invocation -> {
            String rejectedToken = invocation.getArgument(0);
            String current = currentToken.get();
            if (!current.equals(rejectedToken)) {
                // 이미 다른 인스턴스가 저장해 둔 canonical 토큰 재사용 — 전파 대기 불필요
                return new TokenCoordinator.RecoveredToken(current, false);
            }
            if ("admin-token-1".equals(rejectedToken)) {
                // 최근 발급 지문 보호 구간 내 재사용 — 전파 대기 필요
                return new TokenCoordinator.RecoveredToken(current, true);
            }
            TokenCoordinator.TokenIssuer issuer = invocation.getArgument(1);
            String issued = issuer.issue().accessToken();
            currentToken.set(issued);
            tokenStored.countDown();
            // 실제 신규 발급 — 전파 대기 필요
            return new TokenCoordinator.RecoveredToken(issued, true);
        });
        return coordinator;
    }
}
