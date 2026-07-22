package com.kista.adapter.out.toss;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@DisplayName("Toss Redis fencing Lua 통합 테스트")
class TossRedisTokenStoreIT {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private TossRedisTokenStore store;
    private String scope;

    @BeforeAll
    static void connectRedis() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnectRedis() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        store = new TossRedisTokenStore(redisTemplate);
        scope = "it:" + UUID.randomUUID();
    }

    @AfterEach
    void cleanKeys() {
        redisTemplate.delete(keys());
    }

    @Test
    @DisplayName("lease expiry successor는 generation을 증가시키고 stale owner CAS·unlock을 fence한다")
    void successorGenerationFencesDelayedStoreAndOwnerSafeUnlock() throws InterruptedException {
        TossTokenStore.Lease first = store.tryAcquire(
                scope, "owner-1", Duration.ofMillis(100)).orElseThrow();
        TossTokenStore.Lease successor = awaitLease("owner-2", Duration.ofSeconds(2));

        TossTokenStore.StoreResult successorWrite = store.storeIfCurrent(
                successor,
                new TossTokenStore.TokenValue("token-2", 3_600),
                Duration.ofMinutes(55));
        TossTokenStore.StoreResult staleWrite = store.storeIfCurrent(
                first,
                new TossTokenStore.TokenValue("token-1", 3_600),
                Duration.ofMinutes(55));
        store.release(first);

        assertThat(successor.generation()).isEqualTo(first.generation() + 1);
        assertThat(successorWrite).isEqualTo(TossTokenStore.StoreResult.STORED);
        assertThat(staleWrite).isEqualTo(TossTokenStore.StoreResult.STALE);
        assertThat(store.find(scope).orElseThrow())
                .extracting(
                        TossTokenStore.CanonicalToken::accessToken,
                        TossTokenStore.CanonicalToken::generation)
                .containsExactly("token-2", successor.generation());
        assertThat(store.tryAcquire(scope, "owner-3", Duration.ofMinutes(1))).isEmpty();
        assertThat(store.matchesRecentFingerprint(scope, "token-2")).isTrue();

        Long canonicalTtl = redisTemplate.getExpire(
                TossRedisTokenStore.canonicalKey(scope), TimeUnit.MILLISECONDS);
        assertThat(canonicalTtl).isBetween(Duration.ofMinutes(54).toMillis(),
                Duration.ofMinutes(55).toMillis());

        store.release(successor);
        assertThat(store.tryAcquire(scope, "owner-3", Duration.ofMinutes(1))).isPresent();
    }

    private TossTokenStore.Lease awaitLease(String ownerId, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<TossTokenStore.Lease> lease;
        do {
            lease = store.tryAcquire(scope, ownerId, Duration.ofMinutes(1));
            if (lease.isEmpty()) {
                Thread.sleep(10);
            }
        } while (lease.isEmpty() && System.nanoTime() < deadline);
        return lease.orElseThrow(() -> new AssertionError("successor lease acquisition timed out"));
    }

    private List<String> keys() {
        return List.of(
                TossRedisTokenStore.canonicalKey(scope),
                TossRedisTokenStore.leaseKey(scope),
                TossRedisTokenStore.generationKey(scope),
                TossRedisTokenStore.fingerprintKey(scope));
    }
}
