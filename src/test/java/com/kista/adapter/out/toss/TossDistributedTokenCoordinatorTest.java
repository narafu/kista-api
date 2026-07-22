package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TossDistributedTokenCoordinator Redis 분산 발급 조정")
class TossDistributedTokenCoordinatorTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("두 coordinator의 계좌 401 복구가 겹쳐도 OAuth는 한 번만 발급한다")
    void accountRecoveryAcrossCoordinators_issuesOnlyOnce() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        accounts.save("token-0");
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToStore = new CountDownLatch(1);
        CountDownLatch tokenStored = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), "owner-a", duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), "owner-b", duration -> {
            allowOwnerToStore.countDown();
            await(tokenStored);
        });

        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () -> {
            oauthCalls.incrementAndGet();
            ownerIssuing.countDown();
            await(allowOwnerToStore);
            accounts.save("token-1");
            tokenStored.countDown();
            return "token-1";
        };
        AtomicReference<String> ownerResult = new AtomicReference<>();
        AtomicReference<String> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ownerThread = start(failure, () -> ownerResult.set(owner.recoverAccountToken(
                ACCOUNT_ID, "token-0", accounts::find, accounts::invalidate, issuer)));
        assertThat(ownerIssuing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread waiterThread = start(failure, () -> waiterResult.set(waiter.recoverAccountToken(
                ACCOUNT_ID, "token-0", accounts::find, accounts::invalidate, issuer)));

        join(ownerThread);
        join(waiterThread);
        assertThat(failure.get()).isNull();
        assertThat(ownerResult.get()).isEqualTo("token-1");
        assertThat(waiterResult.get()).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("OAuth·DB 저장이 예상 최대 33초를 넘어도 다른 coordinator가 중복 발급하지 않는다")
    void leaseOutlivesExpectedCriticalSection_withoutDuplicateIssuance() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToStore = new CountDownLatch(1);
        CountDownLatch tokenStored = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), "owner-a", duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), "owner-b", duration -> {
            allowOwnerToStore.countDown();
            await(tokenStored);
        });

        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () -> {
            int call = oauthCalls.incrementAndGet();
            if (call == 1) {
                ownerIssuing.countDown();
                await(allowOwnerToStore);
                accounts.save("token-1");
                tokenStored.countDown();
                return "token-1";
            }
            allowOwnerToStore.countDown();
            accounts.save("duplicate-token");
            tokenStored.countDown();
            return "duplicate-token";
        };
        AtomicReference<String> ownerResult = new AtomicReference<>();
        AtomicReference<String> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ownerThread = start(failure, () -> ownerResult.set(owner.getAccountToken(
                ACCOUNT_ID, accounts::find, issuer)));
        assertThat(ownerIssuing.await(5, TimeUnit.SECONDS)).isTrue();
        redis.advance(Duration.ofSeconds(35));
        Thread waiterThread = start(failure, () -> waiterResult.set(waiter.getAccountToken(
                ACCOUNT_ID, accounts::find, issuer)));

        join(ownerThread);
        join(waiterThread);
        assertThat(failure.get()).isNull();
        assertThat(ownerResult.get()).isEqualTo("token-1");
        assertThat(waiterResult.get()).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("두 coordinator의 관리자 401 복구가 겹쳐도 공유 token-1을 반환한다")
    void adminRecoveryAcrossCoordinators_issuesOnlyOnce() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        redis.put(TossDistributedTokenCoordinator.ADMIN_TOKEN_KEY, "admin-token-0", Duration.ofHours(1));
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToStore = new CountDownLatch(1);
        CountDownLatch ownerFinished = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), "owner-a", duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), "owner-b", duration -> {
            allowOwnerToStore.countDown();
            await(ownerFinished);
        });

        TossDistributedTokenCoordinator.AdminTokenIssuer issuer = () -> {
            oauthCalls.incrementAndGet();
            ownerIssuing.countDown();
            await(allowOwnerToStore);
            return new TossDistributedTokenCoordinator.IssuedAdminToken("admin-token-1", 3_600);
        };
        AtomicReference<String> ownerResult = new AtomicReference<>();
        AtomicReference<String> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ownerThread = start(failure, () -> {
            ownerResult.set(owner.recoverAdminToken("admin-token-0", issuer));
            ownerFinished.countDown();
        });
        assertThat(ownerIssuing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread waiterThread = start(failure,
                () -> waiterResult.set(waiter.recoverAdminToken("admin-token-0", issuer)));

        join(ownerThread);
        join(waiterThread);
        assertThat(failure.get()).isNull();
        assertThat(ownerResult.get()).isEqualTo("admin-token-1");
        assertThat(waiterResult.get()).isEqualTo("admin-token-1");
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("lease가 다른 owner로 교체되면 이전 owner가 해제하지 못한다")
    void leaseRelease_doesNotDeleteAnotherOwner() {
        FakeRedis redis = new FakeRedis();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), "owner-a", duration -> {});
        String leaseKey = TossDistributedTokenCoordinator.accountLeaseKey(ACCOUNT_ID);

        String result = coordinator.getAccountToken(ACCOUNT_ID, accounts::find, () -> {
            redis.put(leaseKey, "owner-b", Duration.ofSeconds(15));
            accounts.save("token-1");
            return "token-1";
        });

        assertThat(result).isEqualTo("token-1");
        assertThat(redis.get(leaseKey)).isEqualTo("owner-b");
    }

    @Test
    @DisplayName("최근 fingerprint 2초 동안은 같은 토큰을 보호하고 만료 뒤에는 재발급한다")
    void recentFingerprint_expiresAfterTwoSeconds() {
        FakeRedis redis = new FakeRedis();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), "owner-a", duration -> {});
        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () -> {
            String token = "token-" + oauthCalls.incrementAndGet();
            accounts.save(token);
            return token;
        };

        String issued = coordinator.getAccountToken(ACCOUNT_ID, accounts::find, issuer);
        String protectedToken = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, accounts::find, accounts::invalidate, issuer);
        String fingerprint = redis.get(TossDistributedTokenCoordinator.accountFingerprintKey(ACCOUNT_ID));

        assertThat(protectedToken).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
        assertThat(fingerprint)
                .isEqualTo("3f08aace122ee2368432c1ca23a049bc640bafbf00fdf33a52429f38ba12dbf9")
                .doesNotContain("token-1");

        redis.advance(Duration.ofSeconds(2));
        String reissued = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, accounts::find, accounts::invalidate, issuer);

        assertThat(reissued).isEqualTo("token-2");
        assertThat(oauthCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("관리자 token TTL은 OAuth 만료보다 5분 짧다")
    void adminTokenTtl_isFiveMinutesShorterThanOauthExpiry() {
        FakeRedis redis = new FakeRedis();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), "owner-a", duration -> {});

        String token = coordinator.getAdminToken(
                () -> new TossDistributedTokenCoordinator.IssuedAdminToken("admin-token", 3_600));

        assertThat(token).isEqualTo("admin-token");
        assertThat(redis.ttl(TossDistributedTokenCoordinator.ADMIN_TOKEN_KEY))
                .isEqualTo(Duration.ofMinutes(55));
        assertThat(redis.ttl(TossDistributedTokenCoordinator.ADMIN_FINGERPRINT_KEY))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("다른 owner의 lease 대기는 정해진 polling 횟수 뒤 실패한다")
    void leaseWait_isBoundedWithoutSleepingInTest() {
        FakeRedis redis = new FakeRedis();
        redis.put(TossDistributedTokenCoordinator.accountLeaseKey(ACCOUNT_ID),
                "other-owner", Duration.ofMinutes(1));
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = new TossDistributedTokenCoordinator(
                redis.template(), duration -> polls.incrementAndGet(), () -> "owner-a", 3);

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID, Optional::empty, () -> "token-" + oauthCalls.incrementAndGet()))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("대기 시간 초과");
        assertThat(polls.get()).isEqualTo(3);
        assertThat(oauthCalls.get()).isZero();
    }

    @Test
    @DisplayName("Redis 오류 시 로컬 OAuth 발급으로 우회하지 않는다")
    void redisFailure_failsClosedWithoutIssuing() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(brokenRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(brokenRedis, "owner-a", duration -> {});

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID, Optional::empty, () -> "token-" + oauthCalls.incrementAndGet()))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("Redis");
        assertThat(oauthCalls.get()).isZero();
    }

    private TossDistributedTokenCoordinator coordinator(
            StringRedisTemplate redisTemplate,
            String ownerId,
            TossDistributedTokenCoordinator.PollWaiter pollWaiter
    ) {
        return new TossDistributedTokenCoordinator(redisTemplate, pollWaiter, () -> ownerId, 3);
    }

    private Thread start(AtomicReference<Throwable> failure, Runnable runnable) {
        return Thread.ofVirtual().start(() -> {
            try {
                runnable.run();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });
    }

    private void join(Thread thread) throws InterruptedException {
        thread.join(5_000);
        assertThat(thread.isAlive()).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class InMemoryAccountTokens {
        private final AtomicReference<String> token = new AtomicReference<>();
        private final AtomicReference<OffsetDateTime> expiresAt =
                new AtomicReference<>(OffsetDateTime.MIN);

        Optional<String> find() {
            return expiresAt.get().isAfter(OffsetDateTime.now())
                    ? Optional.ofNullable(token.get())
                    : Optional.empty();
        }

        void save(String accessToken) {
            token.set(accessToken);
            expiresAt.set(OffsetDateTime.now().plusHours(1));
        }

        void invalidate(String rejectedToken) {
            token.compareAndSet(rejectedToken, "__invalidated__");
            expiresAt.set(OffsetDateTime.MIN);
        }
    }

    private static final class FakeRedis {
        private final ConcurrentHashMap<String, Entry> values = new ConcurrentHashMap<>();
        private long nowNanos;

        @SuppressWarnings({"unchecked", "rawtypes"})
        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenAnswer(invocation -> get(invocation.getArgument(0)));
            when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                    .thenAnswer(invocation -> setIfAbsent(
                            invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            doAnswer(invocation -> {
                put(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
            doAnswer(invocation -> {
                List<String> keys = invocation.getArgument(1);
                Object argument = invocation.getArgument(2);
                String expectedOwner = argument instanceof Object[] arguments
                        ? String.valueOf(arguments[0])
                        : String.valueOf(argument);
                return compareAndDelete(keys.get(0), expectedOwner) ? 1L : 0L;
            }).when(template).execute(any(RedisScript.class), anyList(), any(Object[].class));
            return template;
        }

        synchronized void put(String key, String value, Duration ttl) {
            values.put(key, new Entry(value, nowNanos + ttl.toNanos()));
        }

        synchronized String get(String key) {
            Entry entry = liveEntry(key);
            return entry == null ? null : entry.value();
        }

        synchronized Duration ttl(String key) {
            Entry entry = liveEntry(key);
            return entry == null ? Duration.ZERO : Duration.ofNanos(entry.expiresAtNanos() - nowNanos);
        }

        synchronized void advance(Duration duration) {
            nowNanos += duration.toNanos();
        }

        private synchronized boolean setIfAbsent(String key, String value, Duration ttl) {
            if (liveEntry(key) != null) {
                return false;
            }
            put(key, value, ttl);
            return true;
        }

        private synchronized boolean compareAndDelete(String key, String expectedValue) {
            Entry entry = liveEntry(key);
            return entry != null && entry.value().equals(expectedValue) && values.remove(key, entry);
        }

        private Entry liveEntry(String key) {
            Entry entry = values.get(key);
            if (entry != null && entry.expiresAtNanos() <= nowNanos) {
                values.remove(key, entry);
                return null;
            }
            return entry;
        }

        private record Entry(String value, long expiresAtNanos) {}
    }
}
