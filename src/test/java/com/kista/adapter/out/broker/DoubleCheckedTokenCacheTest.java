package com.kista.adapter.out.broker;

import com.kista.domain.port.out.BrokerTokenCachePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("DoubleCheckedTokenCache — double-checked locking 토큰 캐시")
class DoubleCheckedTokenCacheTest {

    private DoubleCheckedTokenCache cache;
    private final UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    // KIS 기준 threshold: 만료 1분 전까지만 유효 토큰으로 인정
    private final Supplier<OffsetDateTime> threshold = () -> OffsetDateTime.now().plusMinutes(1);

    @BeforeEach
    void setUp() {
        cache = new DoubleCheckedTokenCache();
    }

    // (a) 1차 캐시 조회에서 유효 토큰 발견 → fetcher 미호출
    @Test
    @DisplayName("유효 토큰이 캐시에 있으면 발급 함수를 호출하지 않는다")
    void cachedToken_returnsImmediatelyWithoutFetching() {
        BrokerTokenCachePort cachePort = mock(BrokerTokenCachePort.class);
        when(cachePort.findValidToken(eq(accountId), any())).thenReturn(Optional.of("cached-token"));
        AtomicInteger fetchCount = new AtomicInteger(0);
        Supplier<String> fetcher = () -> { fetchCount.incrementAndGet(); return "new-token"; };

        String result = cache.getOrFetch(cachePort, accountId, threshold, fetcher);

        assertThat(result).isEqualTo("cached-token");
        assertThat(fetchCount.get()).isZero();
        // findValidToken은 1차 조회에서만 호출됨
        verify(cachePort, times(1)).findValidToken(eq(accountId), any());
        verify(cachePort, never()).saveToken(any(), any(), any());
    }

    // (b) 1차 캐시 miss → fetcher 1회 호출
    @Test
    @DisplayName("캐시에 토큰이 없으면 발급 함수를 1회 호출하고 결과를 반환한다")
    void cacheMiss_invokesFecher_once() {
        BrokerTokenCachePort cachePort = mock(BrokerTokenCachePort.class);
        // 1차·2차 모두 miss
        when(cachePort.findValidToken(any(), any())).thenReturn(Optional.empty());
        AtomicInteger fetchCount = new AtomicInteger(0);
        Supplier<String> fetcher = () -> { fetchCount.incrementAndGet(); return "new-token"; };

        String result = cache.getOrFetch(cachePort, accountId, threshold, fetcher);

        assertThat(result).isEqualTo("new-token");
        assertThat(fetchCount.get()).isOne();
    }

    // (c) 동시 호출 시 fetcher가 정확히 1회만 실행됨
    @Test
    @DisplayName("두 스레드가 동시에 캐시 miss 상태에서 호출해도 발급 함수는 1회만 실행된다")
    void concurrent_twoCalls_fetchesOnlyOnce() throws InterruptedException {
        InMemoryTokenCache cachePort = new InMemoryTokenCache();
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger(0);

        Supplier<String> fetcher = () -> {
            // 발급 + 캐시 저장 (실제 KIS/Toss AuthApi fetcher가 하는 역할)
            int count = fetchCount.incrementAndGet();
            String token = "fetched-token-" + count;
            cachePort.saveToken(accountId, token, OffsetDateTime.now().plusHours(1));
            return token;
        };

        Runnable task = () -> {
            try { startGun.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.getOrFetch(cachePort, accountId, threshold, fetcher);
        };

        // Virtual Thread 환경 — @Async/CompletableFuture 금지, Thread.ofVirtual 사용
        Thread t1 = Thread.ofVirtual().start(task);
        Thread t2 = Thread.ofVirtual().start(task);

        // 두 스레드를 동시에 출발
        startGun.countDown();
        t1.join(5_000);
        t2.join(5_000);

        // 두 스레드가 모두 완료했는지 확인
        assertThat(t1.isAlive()).isFalse();
        assertThat(t2.isAlive()).isFalse();
        // double-checked locking 덕분에 발급은 정확히 1회
        assertThat(fetchCount.get()).isOne();
    }

    // (d) 만료/만료 임박 토큰 → 재발급 트리거
    @Test
    @DisplayName("이미 만료된 토큰이 캐시에 있으면 threshold 초과로 miss 처리되어 재발급된다")
    void expiredToken_isRejectedByThreshold_andRefetched() {
        InMemoryTokenCache cachePort = new InMemoryTokenCache();
        // 1분 전에 만료된 토큰 저장 — threshold(now+1min)보다 이전이므로 findValidToken에서 empty 반환
        cachePort.saveToken(accountId, "expired-token", OffsetDateTime.now().minusMinutes(1));

        AtomicInteger fetchCount = new AtomicInteger(0);
        Supplier<String> fetcher = () -> {
            fetchCount.incrementAndGet();
            String token = "refreshed-token";
            cachePort.saveToken(accountId, token, OffsetDateTime.now().plusHours(1));
            return token;
        };

        String result = cache.getOrFetch(cachePort, accountId, threshold, fetcher);

        assertThat(result).isEqualTo("refreshed-token");
        assertThat(fetchCount.get()).isOne();
    }

    @Test
    @DisplayName("늦은 401이 최근 발급 세대를 거절해도 같은 토큰을 재사용한다")
    void staggeredRecovery_reusesRecentlyIssuedGeneration() throws InterruptedException {
        InMemoryTokenCache cachePort = new InMemoryTokenCache();
        cachePort.saveToken(accountId, "token-0", OffsetDateTime.now().plusHours(1));
        CountDownLatch token1Issued = new CountDownLatch(1);
        CountDownLatch lateRequestRecovered = new CountDownLatch(1);
        AtomicInteger fetchCount = new AtomicInteger();
        AtomicReference<String> firstResult = new AtomicReference<>();
        AtomicReference<String> lateResult = new AtomicReference<>();
        Supplier<String> fetcher = () -> {
            String token = "token-" + fetchCount.incrementAndGet();
            cachePort.saveToken(accountId, token, OffsetDateTime.now().plusHours(1));
            return token;
        };

        Thread firstRequest = Thread.ofVirtual().start(() -> {
            firstResult.set(cache.recoverRejectedToken(
                    cachePort, accountId, "token-0", threshold, Duration.ofSeconds(2), fetcher));
            token1Issued.countDown();
            await(lateRequestRecovered);
        });
        Thread lateRequest = Thread.ofVirtual().start(() -> {
            await(token1Issued);
            String rejectedToken = cache.getOrFetch(cachePort, accountId, threshold, fetcher);
            lateResult.set(cache.recoverRejectedToken(
                    cachePort, accountId, rejectedToken, threshold, Duration.ofSeconds(2), fetcher));
            lateRequestRecovered.countDown();
        });

        firstRequest.join(5_000);
        lateRequest.join(5_000);

        assertThat(firstRequest.isAlive()).isFalse();
        assertThat(lateRequest.isAlive()).isFalse();
        assertThat(firstResult.get()).isEqualTo("token-1");
        assertThat(lateResult.get()).isEqualTo("token-1");
        assertThat(fetchCount.get()).isOne();
    }

    @Test
    @DisplayName("거절 토큰보다 캐시의 현재 세대가 새로우면 현재 토큰을 반환한다")
    void recovery_returnsCurrentToken_whenRejectedGenerationIsStale() {
        InMemoryTokenCache cachePort = new InMemoryTokenCache();
        cachePort.saveToken(accountId, "token-1", OffsetDateTime.now().plusHours(1));
        AtomicInteger fetchCount = new AtomicInteger();

        String result = cache.recoverRejectedToken(
                cachePort, accountId, "token-0", threshold, Duration.ofSeconds(2),
                () -> "token-" + fetchCount.incrementAndGet());

        assertThat(result).isEqualTo("token-1");
        assertThat(fetchCount.get()).isZero();
    }

    private void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    // --- 테스트 전용 인메모리 BrokerTokenCachePort 구현 ---

    // 실제 만료 시각 기반 검증이 필요한 테스트((c),(d))에서 사용
    static class InMemoryTokenCache implements BrokerTokenCachePort {

        private final ConcurrentHashMap<UUID, String> tokens = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<UUID, OffsetDateTime> expiries = new ConcurrentHashMap<>();

        @Override
        public Optional<String> findValidToken(UUID accountId, OffsetDateTime threshold) {
            String token = tokens.get(accountId);
            OffsetDateTime expiry = expiries.get(accountId);
            // 만료 시각이 threshold보다 미래여야 유효 토큰으로 인정
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
