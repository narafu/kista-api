package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossApiException;
import com.kista.domain.port.out.BrokerTokenCachePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

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

    private TossHttpClient newClient(TossAuthApi authApi) {
        return new TossHttpClient(tossRestTemplate, authApi, "http://toss.test");
    }

    private HttpClientErrorException unauthorized() {
        return HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
    }

    @Test
    @DisplayName("늦게 시작한 계좌 요청의 token-1 401은 최근 발급 세대를 재사용")
    @SuppressWarnings("unchecked")
    void staggeredAccountRequests_reuseRecentlyIssuedTokenGeneration() throws InterruptedException {
        CountDownLatch token1Stored = new CountDownLatch(1);
        InMemoryTokenCache tokenCache = new InMemoryTokenCache() {
            @Override
            public void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
                super.saveToken(accountId, accessToken, expiresAt);
                if (accessToken.equals("token-1")) {
                    token1Stored.countDown();
                }
            }
        };
        tokenCache.saveToken(ACCOUNT.id(), "token-0", OffsetDateTime.now().plusHours(1));
        TossDistributedTokenCoordinator tokenCoordinator = mockAccountCoordinator();
        TossAuthApi realAuthApi = new TossAuthApi(
                tossRestTemplate, tokenCache, tokenCoordinator,
                "http://toss.test", "admin-id", "admin-secret");
        TossHttpClient client = newClient(realAuthApi);
        AtomicInteger oauthFetchCount = new AtomicInteger();
        CountDownLatch requestCRejectedToken1 = new CountDownLatch(1);
        AtomicInteger requestCToken1Calls = new AtomicInteger();

        when(tossRestTemplate.exchange(eq("http://toss.test/oauth2/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(TossAuthApi.TokenResponse.class)))
                .thenAnswer(invocation -> {
                    int generation = oauthFetchCount.incrementAndGet();
                    return ResponseEntity.ok(new TossAuthApi.TokenResponse("token-" + generation, 86400L));
                });
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> request = invocation.getArgument(2);
                    String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    String threadName = Thread.currentThread().getName();
                    if (threadName.equals("account-request-a") && authorization.equals("Bearer token-0")) {
                        throw unauthorized();
                    }
                    if (threadName.equals("account-request-a") && authorization.equals("Bearer token-1")) {
                        await(requestCRejectedToken1);
                        return ResponseEntity.ok("A:token-1");
                    }
                    if (threadName.equals("account-request-c") && authorization.equals("Bearer token-1")
                            && requestCToken1Calls.incrementAndGet() == 1) {
                        requestCRejectedToken1.countDown();
                        throw unauthorized();
                    }
                    return ResponseEntity.ok("C:" + authorization.substring("Bearer ".length()));
                });

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
        TossDistributedTokenCoordinator tokenCoordinator = mockAdminCoordinator("admin-token-0");
        TossAuthApi realAuthApi = new TossAuthApi(
                tossRestTemplate, new InMemoryTokenCache(), tokenCoordinator,
                "http://toss.test", "admin-id", "admin-secret");
        TossHttpClient client = newClient(realAuthApi);
        AtomicInteger oauthFetchCount = new AtomicInteger();
        CountDownLatch token1Issued = new CountDownLatch(1);
        CountDownLatch requestCRejectedToken1 = new CountDownLatch(1);
        AtomicInteger requestCToken1Calls = new AtomicInteger();

        when(tossRestTemplate.exchange(eq("http://toss.test/oauth2/token"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(TossAuthApi.TokenResponse.class)))
                .thenAnswer(invocation -> {
                    int generation = oauthFetchCount.incrementAndGet();
                    if (generation == 1) {
                        token1Issued.countDown();
                    }
                    return ResponseEntity.ok(new TossAuthApi.TokenResponse("admin-token-" + generation, 86400L));
                });
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    HttpEntity<?> request = invocation.getArgument(2);
                    String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    String threadName = Thread.currentThread().getName();
                    if (threadName.equals("admin-request-a") && authorization.equals("Bearer admin-token-0")) {
                        throw unauthorized();
                    }
                    if (threadName.equals("admin-request-a") && authorization.equals("Bearer admin-token-1")) {
                        await(requestCRejectedToken1);
                        return ResponseEntity.ok("A:admin-token-1");
                    }
                    if (threadName.equals("admin-request-c") && authorization.equals("Bearer admin-token-1")
                            && requestCToken1Calls.incrementAndGet() == 1) {
                        requestCRejectedToken1.countDown();
                        throw unauthorized();
                    }
                    return ResponseEntity.ok("C:" + authorization.substring("Bearer ".length()));
                });

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

    @Test
    @DisplayName("신규 토큰 전파가 지연되면 같은 신규 토큰으로 재시도")
    @SuppressWarnings("unchecked")
    void retriesSameFreshToken_whenPropagationIsDelayed() {
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0")))
                .thenReturn("token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {});

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0");
        verify(tossAuthApi, never()).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-1");
        verify(tossAuthApi).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
        ArgumentCaptor<HttpEntity<?>> requestCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                requestCaptor.capture(), any(ParameterizedTypeReference.class));
        assertThat(requestCaptor.getAllValues())
                .extracting(request -> request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .containsExactly("Bearer token-0", "Bearer token-1", "Bearer token-1");
        InOrder retryOrder = inOrder(tossAuthApi, tossRestTemplate);
        retryOrder.verify(tossAuthApi).getToken(ACCOUNT.id(), "cid", "csecret");
        retryOrder.verify(tossRestTemplate).exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class));
        retryOrder.verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0");
        retryOrder.verify(tossRestTemplate, times(2)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("재시도 한도 후에도 신규 토큰은 무효화하지 않음")
    void throwsAfterRetryLimit_withoutInvalidatingFreshToken() {
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        when(tossAuthApi.recoverToken(any(), anyString(), anyString(), eq("token-0")))
                .thenReturn("token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.get(PATH, ACCOUNT, new LinkedMultiValueMap<>(),
                new ParameterizedTypeReference<String>() {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-0");
        verify(tossAuthApi, never()).recoverToken(ACCOUNT.id(), "cid", "csecret", "token-1");
        verify(tossAuthApi).getToken(eq(ACCOUNT.id()), anyString(), anyString());
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("공통 API(getCommon) 401도 최대 2회까지 백오프 재시도 후 성공")
    void getCommon_retriesTwiceAfter401_thenSucceeds() {
        when(tossAuthApi.getAdminToken()).thenReturn("admin-token-0");
        when(tossAuthApi.recoverAdminToken("admin-token-0")).thenReturn("admin-token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized())
                .thenThrow(unauthorized())
                .thenReturn(new ResponseEntity<>("OK", HttpStatus.OK));

        String result = newClient().getCommon(PATH, new LinkedMultiValueMap<>(), String.class);

        assertThat(result).isEqualTo("OK");
        verify(tossAuthApi).recoverAdminToken("admin-token-0");
        verify(tossAuthApi, never()).recoverAdminToken("admin-token-1");
        verify(tossAuthApi).getAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("공통 API 401이 세 번(최초+2차 재시도 모두) 발생하면 TossApiException")
    void getCommon_throwsTossApiException_when401Persists() {
        when(tossAuthApi.getAdminToken()).thenReturn("admin-token-0");
        when(tossAuthApi.recoverAdminToken("admin-token-0")).thenReturn("admin-token-1");
        when(tossRestTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(unauthorized());

        TossHttpClient client = newClient();
        assertThatThrownBy(() -> client.getCommon(PATH, new LinkedMultiValueMap<>(), String.class))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("토큰 재시도 실패");

        verify(tossAuthApi).recoverAdminToken("admin-token-0");
        verify(tossAuthApi, never()).recoverAdminToken("admin-token-1");
        verify(tossAuthApi).getAdminToken();
        verify(tossRestTemplate, times(3)).exchange(anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private TossDistributedTokenCoordinator mockAccountCoordinator() {
        TossDistributedTokenCoordinator coordinator = org.mockito.Mockito.mock(
                TossDistributedTokenCoordinator.class);
        when(coordinator.getAccountToken(any(), any(), any())).thenAnswer(invocation -> {
            Supplier<Optional<String>> currentToken = invocation.getArgument(1);
            TossDistributedTokenCoordinator.AccountTokenIssuer issuer = invocation.getArgument(2);
            return currentToken.get().orElseGet(issuer::issue);
        });
        when(coordinator.recoverAccountToken(any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String rejectedToken = invocation.getArgument(1);
                    Supplier<Optional<String>> currentToken = invocation.getArgument(2);
                    Consumer<String> invalidator = invocation.getArgument(3);
                    TossDistributedTokenCoordinator.AccountTokenIssuer issuer = invocation.getArgument(4);
                    Optional<String> current = currentToken.get();
                    if (current.isPresent() && !current.get().equals(rejectedToken)) {
                        return current.get();
                    }
                    if ("token-1".equals(rejectedToken)) {
                        return rejectedToken;
                    }
                    invalidator.accept(rejectedToken);
                    return issuer.issue();
                });
        return coordinator;
    }

    private TossDistributedTokenCoordinator mockAdminCoordinator(String initialToken) {
        TossDistributedTokenCoordinator coordinator = org.mockito.Mockito.mock(
                TossDistributedTokenCoordinator.class);
        AtomicReference<String> currentToken = new AtomicReference<>(initialToken);
        when(coordinator.getAdminToken(any())).thenAnswer(invocation -> currentToken.get());
        when(coordinator.recoverAdminToken(anyString(), any())).thenAnswer(invocation -> {
            String rejectedToken = invocation.getArgument(0);
            String current = currentToken.get();
            if (!current.equals(rejectedToken) || "admin-token-1".equals(rejectedToken)) {
                return current;
            }
            TossDistributedTokenCoordinator.AdminTokenIssuer issuer = invocation.getArgument(1);
            String issued = issuer.issue().accessToken();
            currentToken.set(issued);
            return issued;
        });
        return coordinator;
    }

    private static class InMemoryTokenCache implements BrokerTokenCachePort {

        private final ConcurrentHashMap<UUID, String> tokens = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, OffsetDateTime> expiries = new ConcurrentHashMap<>();

        @Override
        public Optional<String> findValidToken(UUID accountId, OffsetDateTime threshold) {
            String token = tokens.get(accountId);
            OffsetDateTime expiry = expiries.get(accountId);
            if (token == null || expiry == null || !expiry.isAfter(threshold)) {
                return Optional.empty();
            }
            return Optional.of(token);
        }

        @Override
        public void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
            tokens.put(accountId, accessToken);
            expiries.put(accountId, expiresAt);
        }

        @Override
        public void invalidateToken(UUID accountId, String rejectedAccessToken, OffsetDateTime invalidatedAt) {
            tokens.computeIfPresent(accountId, (id, token) -> {
                if (token.equals(rejectedAccessToken)) {
                    expiries.put(id, invalidatedAt);
                    return INVALIDATED_TOKEN;
                }
                return token;
            });
        }
    }
}
