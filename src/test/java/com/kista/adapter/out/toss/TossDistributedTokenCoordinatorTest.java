package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TossDistributedTokenCoordinator PostgreSQL advisory lock 분산 발급 조정")
class TossDistributedTokenCoordinatorTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("OAuth critical section은 caller transaction을 suspend한다")
    void criticalSection_doesNotRunInCallerTransaction() {
        Transactional transactional = TossDistributedTokenCoordinator.class
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    @DisplayName("두 coordinator의 계좌 401 복구가 겹쳐도 OAuth는 한 번만 발급한다")
    void accountRecoveryAcrossCoordinators_issuesOnlyOnce() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        accounts.save("token-0");
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToPersist = new CountDownLatch(1);
        CountDownLatch tokenPersisted = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), locks, duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), locks, duration -> {
            allowOwnerToPersist.countDown();
            await(tokenPersisted);
        });

        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () -> {
            oauthCalls.incrementAndGet();
            ownerIssuing.countDown();
            await(allowOwnerToPersist);
            return accountToken("token-1");
        };
        TossDistributedTokenCoordinator.AccountTokenPersister persister = token -> {
            accounts.persist(token);
            tokenPersisted.countDown();
        };
        AtomicReference<String> ownerResult = new AtomicReference<>();
        AtomicReference<String> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ownerThread = start(failure, () -> ownerResult.set(owner.recoverAccountToken(
                ACCOUNT_ID, "token-0", accounts::find, accounts::invalidate, issuer, persister)));
        assertThat(ownerIssuing.await(5, TimeUnit.SECONDS)).isTrue();
        Thread waiterThread = start(failure, () -> waiterResult.set(waiter.recoverAccountToken(
                ACCOUNT_ID, "token-0", accounts::find, accounts::invalidate, issuer, persister)));

        join(ownerThread);
        join(waiterThread);
        assertThat(failure.get()).isNull();
        assertThat(ownerResult.get()).isEqualTo("token-1");
        assertThat(waiterResult.get()).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("세션 lock owner가 7일 동안 critical section을 유지해도 두 번째 owner는 없다")
    void staleDuration_hasNoTtlAndNeverCreatesSecondOwner() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToPersist = new CountDownLatch(1);
        CountDownLatch tokenPersisted = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), locks, duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), locks, duration -> {
            allowOwnerToPersist.countDown();
            await(tokenPersisted);
        });
        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () -> {
            oauthCalls.incrementAndGet();
            ownerIssuing.countDown();
            await(allowOwnerToPersist);
            return accountToken("token-1");
        };
        TossDistributedTokenCoordinator.AccountTokenPersister persister = token -> {
            accounts.persist(token);
            tokenPersisted.countDown();
        };
        AtomicReference<String> waiterResult = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread ownerThread = start(failure, () -> owner.getAccountToken(
                ACCOUNT_ID, accounts::find, issuer, persister));
        assertThat(ownerIssuing.await(5, TimeUnit.SECONDS)).isTrue();
        locks.advance(Duration.ofDays(7));
        Thread waiterThread = start(failure, () -> waiterResult.set(waiter.getAccountToken(
                ACCOUNT_ID, accounts::find, issuer, persister)));

        join(ownerThread);
        join(waiterThread);
        assertThat(failure.get()).isNull();
        assertThat(waiterResult.get()).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("두 coordinator의 관리자 401 복구가 겹쳐도 공유 token-1을 반환한다")
    void adminRecoveryAcrossCoordinators_issuesOnlyOnce() throws InterruptedException {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        redis.put(TossDistributedTokenCoordinator.ADMIN_TOKEN_KEY, "admin-token-0", Duration.ofHours(1));
        CountDownLatch ownerIssuing = new CountDownLatch(1);
        CountDownLatch allowOwnerToStore = new CountDownLatch(1);
        CountDownLatch ownerFinished = new CountDownLatch(1);
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator owner = coordinator(redis.template(), locks, duration -> {});
        TossDistributedTokenCoordinator waiter = coordinator(redis.template(), locks, duration -> {
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
    @DisplayName("세션 handle release 후에만 다음 owner가 lock을 획득한다")
    void advisoryLock_releaseAllowsNextOwner() {
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        long lockKey = TossDistributedTokenCoordinator.accountLockKey(ACCOUNT_ID);
        TossTokenIssuanceLock.Handle first = locks.tryAcquire(lockKey).orElseThrow();

        assertThat(locks.tryAcquire(lockKey)).isEmpty();
        first.close();

        TossTokenIssuanceLock.Handle second = locks.tryAcquire(lockKey).orElseThrow();
        assertThat(second).isNotNull();
        second.close();
    }

    @Test
    @DisplayName("세션·프로세스 crash로 owner가 끊기면 lock이 자동 해제된다")
    void advisoryLock_sessionCrashReleasesOwner() {
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        long lockKey = TossDistributedTokenCoordinator.accountLockKey(ACCOUNT_ID);
        locks.tryAcquire(lockKey).orElseThrow();
        locks.advance(Duration.ofDays(7));
        assertThat(locks.tryAcquire(lockKey)).isEmpty();

        locks.crashOwner(lockKey);

        TossTokenIssuanceLock.Handle recovered = locks.tryAcquire(lockKey).orElseThrow();
        assertThat(recovered).isNotNull();
        recovered.close();
    }

    @Test
    @DisplayName("계좌·관리자 scope는 안정적이고 서로 다른 signed 64-bit lock key를 사용한다")
    void lockKeys_areStableAndScoped() {
        long accountKey = TossDistributedTokenCoordinator.accountLockKey(ACCOUNT_ID);

        assertThat(accountKey)
                .isEqualTo(437_200_395_862_100_775L)
                .isEqualTo(TossDistributedTokenCoordinator.accountLockKey(ACCOUNT_ID));
        assertThat(TossDistributedTokenCoordinator.ADMIN_LOCK_KEY)
                .isEqualTo(851_870_195_120_982_093L)
                .isNotEqualTo(accountKey);
        assertThat(accountKey).isNotEqualTo(
                TossDistributedTokenCoordinator.accountLockKey(UUID.fromString(
                        "00000000-0000-0000-0000-000000000002")));
    }

    @Test
    @DisplayName("다른 owner의 advisory lock 대기는 정해진 polling 횟수 뒤 실패한다")
    void advisoryLockWait_isBoundedWithoutSleepingInTest() {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        TossTokenIssuanceLock.Handle owner = locks.tryAcquire(
                TossDistributedTokenCoordinator.accountLockKey(ACCOUNT_ID)).orElseThrow();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = new TossDistributedTokenCoordinator(
                redis.template(), locks, duration -> polls.incrementAndGet(), 3);

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID,
                Optional::empty,
                () -> accountToken("token-" + oauthCalls.incrementAndGet()),
                token -> {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("advisory lock 대기 시간 초과");
        assertThat(polls.get()).isEqualTo(3);
        assertThat(oauthCalls.get()).isZero();
        owner.close();
    }

    @Test
    @DisplayName("최근 fingerprint는 2초 동안 같은 토큰을 보호하고 만료 뒤에는 재발급한다")
    void recentFingerprint_expiresAfterTwoSeconds() {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), locks, duration -> {});
        TossDistributedTokenCoordinator.AccountTokenIssuer issuer = () ->
                accountToken("token-" + oauthCalls.incrementAndGet());

        String issued = coordinator.getAccountToken(
                ACCOUNT_ID, accounts::find, issuer, accounts::persist);
        String protectedToken = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, accounts::find, accounts::invalidate, issuer, accounts::persist);
        String fingerprint = redis.get(TossDistributedTokenCoordinator.accountFingerprintKey(ACCOUNT_ID));

        assertThat(protectedToken).isEqualTo("token-1");
        assertThat(oauthCalls.get()).isOne();
        assertThat(fingerprint)
                .isEqualTo("3f08aace122ee2368432c1ca23a049bc640bafbf00fdf33a52429f38ba12dbf9")
                .doesNotContain("token-1");

        redis.advance(Duration.ofSeconds(2));
        String reissued = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, accounts::find, accounts::invalidate, issuer, accounts::persist);

        assertThat(reissued).isEqualTo("token-2");
        assertThat(oauthCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fingerprint 저장 실패는 새 계좌 토큰을 DB에 commit하지 않고 fail-closed 한다")
    void fingerprintFailure_happensBeforeAccountPersistence() {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        InMemoryAccountTokens accounts = new InMemoryAccountTokens();
        AtomicInteger oauthCalls = new AtomicInteger();
        AtomicInteger persistCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), locks, duration -> {});
        redis.failNextSet(TossDistributedTokenCoordinator.accountFingerprintKey(ACCOUNT_ID));

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID,
                accounts::find,
                () -> accountToken("token-" + oauthCalls.incrementAndGet()),
                token -> {
                    persistCalls.incrementAndGet();
                    accounts.persist(token);
                }))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("Redis");

        assertThat(accounts.find()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("관리자 token TTL은 OAuth 만료보다 5분 짧고 fingerprint는 먼저 저장한다")
    void adminTokenTtl_isFiveMinutesShorterAndFingerprintIsStoredFirst() {
        FakeRedis redis = new FakeRedis();
        FakeAdvisoryLock locks = new FakeAdvisoryLock();
        TossDistributedTokenCoordinator coordinator = coordinator(redis.template(), locks, duration -> {});

        String token = coordinator.getAdminToken(
                () -> new TossDistributedTokenCoordinator.IssuedAdminToken("admin-token", 3_600));

        assertThat(token).isEqualTo("admin-token");
        assertThat(redis.writeOrder())
                .containsExactly(
                        TossDistributedTokenCoordinator.ADMIN_FINGERPRINT_KEY,
                        TossDistributedTokenCoordinator.ADMIN_TOKEN_KEY);
        assertThat(redis.ttl(TossDistributedTokenCoordinator.ADMIN_TOKEN_KEY))
                .isEqualTo(Duration.ofMinutes(55));
        assertThat(redis.ttl(TossDistributedTokenCoordinator.ADMIN_FINGERPRINT_KEY))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("Redis 오류 시 로컬 OAuth 발급으로 우회하지 않는다")
    void redisFailure_failsClosedWithoutIssuing() {
        StringRedisTemplate brokenRedis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(brokenRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new IllegalStateException("redis unavailable"));
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(
                brokenRedis, new FakeAdvisoryLock(), duration -> {});

        assertThatThrownBy(() -> coordinator.recoverAccountToken(
                ACCOUNT_ID,
                "rejected-token",
                Optional::empty,
                rejected -> {},
                () -> accountToken("token-" + oauthCalls.incrementAndGet()),
                token -> {}))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("Redis");
        assertThat(oauthCalls.get()).isZero();
    }

    private TossDistributedTokenCoordinator coordinator(
            StringRedisTemplate redisTemplate,
            TossTokenIssuanceLock locks,
            TossDistributedTokenCoordinator.PollWaiter pollWaiter
    ) {
        return new TossDistributedTokenCoordinator(redisTemplate, locks, pollWaiter, 3);
    }

    private static TossDistributedTokenCoordinator.IssuedAccountToken accountToken(String token) {
        return new TossDistributedTokenCoordinator.IssuedAccountToken(
                token, OffsetDateTime.now().plusHours(1));
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

        void persist(TossDistributedTokenCoordinator.IssuedAccountToken issuedToken) {
            token.set(issuedToken.accessToken());
            expiresAt.set(issuedToken.expiresAt());
        }

        void invalidate(String rejectedToken) {
            token.compareAndSet(rejectedToken, "__invalidated__");
            expiresAt.set(OffsetDateTime.MIN);
        }
    }

    private static final class FakeAdvisoryLock implements TossTokenIssuanceLock {
        private final ConcurrentHashMap<Long, Session> owners = new ConcurrentHashMap<>();
        private final AtomicInteger sessionIds = new AtomicInteger();

        @Override
        public Optional<Handle> tryAcquire(long lockKey) {
            Session session = new Session(sessionIds.incrementAndGet());
            if (owners.putIfAbsent(lockKey, session) != null) {
                return Optional.empty();
            }
            return Optional.of(() -> owners.remove(lockKey, session));
        }

        void crashOwner(long lockKey) {
            owners.remove(lockKey);
        }

        void advance(Duration ignored) {
            // PostgreSQL session advisory locks have no TTL; elapsed time cannot release ownership.
        }

        private record Session(int id) {}
    }

    private static final class FakeRedis {
        private final ConcurrentHashMap<String, Entry> values = new ConcurrentHashMap<>();
        private final java.util.List<String> writeOrder = new java.util.ArrayList<>();
        private String failNextSetKey;
        private long nowNanos;

        StringRedisTemplate template() {
            StringRedisTemplate template = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOps = mock(ValueOperations.class);
            when(template.opsForValue()).thenReturn(valueOps);
            when(valueOps.get(anyString())).thenAnswer(invocation -> get(invocation.getArgument(0)));
            doAnswer(invocation -> {
                put(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                return null;
            }).when(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
            return template;
        }

        synchronized void put(String key, String value, Duration ttl) {
            if (key.equals(failNextSetKey)) {
                failNextSetKey = null;
                throw new IllegalStateException("redis unavailable");
            }
            writeOrder.add(key);
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

        synchronized void failNextSet(String key) {
            failNextSetKey = key;
        }

        synchronized java.util.List<String> writeOrder() {
            return java.util.List.copyOf(writeOrder);
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
