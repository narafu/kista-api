package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Toss Redis fencing token coordinator")
class TossDistributedTokenCoordinatorTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    @DisplayName("lease 만료 successor generation은 지연된 이전 owner의 canonical write를 fence한다")
    void leaseExpiry_successorGenerationRejectsDelayedStaleWriter() {
        FakeTokenStore store = new FakeTokenStore();
        TossDistributedTokenCoordinator owner = coordinator(store, "owner-1");
        TossDistributedTokenCoordinator successor = coordinator(store, "owner-2");
        AtomicReference<String> successorResult = new AtomicReference<>();
        AtomicInteger oauthCalls = new AtomicInteger();

        String ownerResult = owner.getAccountToken(ACCOUNT_ID, () -> {
            oauthCalls.incrementAndGet();
            store.advance(Duration.ofSeconds(61));
            successorResult.set(successor.getAccountToken(
                    ACCOUNT_ID, () -> token("token-2", oauthCalls)));
            return new TossTokenStore.TokenValue("token-1", 3_600);
        });

        assertThat(ownerResult).isEqualTo("token-2");
        assertThat(successorResult.get()).isEqualTo("token-2");
        assertThat(oauthCalls.get()).isEqualTo(2);
        assertThat(store.find("account:" + ACCOUNT_ID).orElseThrow())
                .extracting(
                        TossTokenStore.CanonicalToken::accessToken,
                        TossTokenStore.CanonicalToken::generation)
                .containsExactly("token-2", 2L);
        assertThat(store.staleWriteRejections()).isOne();
    }

    @Test
    @DisplayName("만료된 owner의 unlock은 successor lease를 삭제하지 않는다")
    void ownerSafeUnlock_preservesSuccessorLease() {
        FakeTokenStore store = new FakeTokenStore();
        TossTokenStore.Lease first = store.tryAcquire(
                "account:" + ACCOUNT_ID, "owner-1", Duration.ofSeconds(60)).orElseThrow();
        store.advance(Duration.ofSeconds(61));
        TossTokenStore.Lease successor = store.tryAcquire(
                "account:" + ACCOUNT_ID, "owner-2", Duration.ofSeconds(60)).orElseThrow();

        store.release(first);

        assertThat(store.tryAcquire(
                "account:" + ACCOUNT_ID, "owner-3", Duration.ofSeconds(60))).isEmpty();
        store.release(successor);
        assertThat(store.tryAcquire(
                "account:" + ACCOUNT_ID, "owner-3", Duration.ofSeconds(60))).isPresent();
    }

    @Test
    @DisplayName("lease 경합은 정해진 polling 횟수 뒤 OAuth 없이 실패한다")
    void leaseWait_isBoundedWithoutIssuing() {
        FakeTokenStore store = new FakeTokenStore();
        store.tryAcquire("account:" + ACCOUNT_ID, "owner", Duration.ofMinutes(1)).orElseThrow();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = new TossDistributedTokenCoordinator(
                store, ignored -> polls.incrementAndGet(), () -> "waiter", 3);

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID, () -> token("never", oauthCalls)))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("lease 대기 시간 초과");
        assertThat(polls.get()).isEqualTo(3);
        assertThat(oauthCalls.get()).isZero();
    }

    @Test
    @DisplayName("account와 admin canonical token TTL은 OAuth 만료보다 5분 짧다")
    void canonicalTokens_useFiveMinuteExpiryMargin() {
        FakeTokenStore store = new FakeTokenStore();
        TossDistributedTokenCoordinator coordinator = coordinator(store, "owner");

        String account = coordinator.getAccountToken(
                ACCOUNT_ID, () -> new TossTokenStore.TokenValue("account-token", 3_600));
        String admin = coordinator.getAdminToken(
                () -> new TossTokenStore.TokenValue("admin-token", 7_200));

        assertThat(account).isEqualTo("account-token");
        assertThat(admin).isEqualTo("admin-token");
        assertThat(store.ttl("account:" + ACCOUNT_ID)).isEqualTo(Duration.ofMinutes(55));
        assertThat(store.ttl("admin")).isEqualTo(Duration.ofMinutes(115));
        assertThat(store.find("account:" + ACCOUNT_ID).orElseThrow().generation()).isEqualTo(1L);
        assertThat(store.find("admin").orElseThrow().generation()).isEqualTo(1L);
    }

    @Test
    @DisplayName("최근 generation fingerprint는 같은 token의 전파 중 401을 2초 보호한다")
    void recentFingerprint_protectsPropagationThenExpires() {
        FakeTokenStore store = new FakeTokenStore();
        TossDistributedTokenCoordinator coordinator = coordinator(store, "owner");
        AtomicInteger oauthCalls = new AtomicInteger();

        String issued = coordinator.getAccountToken(
                ACCOUNT_ID, () -> token("token-1", oauthCalls));
        TossDistributedTokenCoordinator.RecoveredToken protectedToken = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, () -> token("token-2", oauthCalls));

        assertThat(protectedToken.accessToken()).isEqualTo("token-1");
        // 최근 발급 지문 보호 구간 내 재사용 — 전파 지연 위험이 남아있어 freshlyIssued=true
        assertThat(protectedToken.freshlyIssued()).isTrue();
        assertThat(oauthCalls.get()).isOne();
        assertThat(store.fingerprint("account:" + ACCOUNT_ID))
                .isEqualTo("3f08aace122ee2368432c1ca23a049bc640bafbf00fdf33a52429f38ba12dbf9")
                .doesNotContain("token-1");

        store.advance(Duration.ofSeconds(2));
        TossDistributedTokenCoordinator.RecoveredToken reissued = coordinator.recoverAccountToken(
                ACCOUNT_ID, issued, () -> token("token-2", oauthCalls));

        assertThat(reissued.accessToken()).isEqualTo("token-2");
        // 실제 신규 OAuth 발급 — 전파 지연 위험이 있어 freshlyIssued=true
        assertThat(reissued.freshlyIssued()).isTrue();
        assertThat(oauthCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 canonical 토큰이 rejected token과 다르면 재발급 없이 재사용하고 freshlyIssued=false")
    void recoverAccountToken_reusesDifferentCanonicalToken_withoutPropagationWait() {
        FakeTokenStore store = new FakeTokenStore();
        TossDistributedTokenCoordinator coordinator = coordinator(store, "owner");
        AtomicInteger oauthCalls = new AtomicInteger();

        // 이미 다른 인스턴스가 발급해 저장해 둔 canonical 토큰(token-1)이 존재하는 상태를 시뮬레이션
        coordinator.getAccountToken(ACCOUNT_ID, () -> token("token-1", oauthCalls));
        TossDistributedTokenCoordinator.RecoveredToken recovered = coordinator.recoverAccountToken(
                ACCOUNT_ID, "stale-rejected-token", () -> token("token-2", oauthCalls));

        assertThat(recovered.accessToken()).isEqualTo("token-1");
        // Redis 읽기만으로 이미 다른 canonical 토큰을 재사용 — 전파 지연 위험 없어 freshlyIssued=false
        assertThat(recovered.freshlyIssued()).isFalse();
        assertThat(oauthCalls.get()).isOne();
    }

    @Test
    @DisplayName("Redis read 오류는 OAuth fallback 없이 fail-closed 한다")
    void redisFailure_failsClosedWithoutIssuing() {
        FakeTokenStore store = new FakeTokenStore();
        store.failReads();
        AtomicInteger oauthCalls = new AtomicInteger();
        TossDistributedTokenCoordinator coordinator = coordinator(store, "owner");

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID, () -> token("never", oauthCalls)))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("Redis");
        assertThat(oauthCalls.get()).isZero();
    }

    @Test
    @DisplayName("만료가 5분 이하인 OAuth token은 canonical에 저장하지 않는다")
    void tooShortToken_failsClosedWithoutCanonicalWrite() {
        FakeTokenStore store = new FakeTokenStore();
        TossDistributedTokenCoordinator coordinator = coordinator(store, "owner");

        assertThatThrownBy(() -> coordinator.getAccountToken(
                ACCOUNT_ID, () -> new TossTokenStore.TokenValue("short-token", 300)))
                .isInstanceOf(TossApiException.class)
                .hasMessageContaining("5분 이하");
        assertThat(store.find("account:" + ACCOUNT_ID)).isEmpty();
    }

    private TossDistributedTokenCoordinator coordinator(FakeTokenStore store, String ownerId) {
        return new TossDistributedTokenCoordinator(store, ignored -> {}, () -> ownerId, 3);
    }

    private TossTokenStore.TokenValue token(String value, AtomicInteger calls) {
        calls.incrementAndGet();
        return new TossTokenStore.TokenValue(value, 3_600);
    }

    private static final class FakeTokenStore implements TossTokenStore {
        private final Map<String, CanonicalEntry> canonical = new HashMap<>();
        private final Map<String, LeaseEntry> leases = new HashMap<>();
        private final Map<String, Long> generations = new HashMap<>();
        private final Map<String, FingerprintEntry> fingerprints = new HashMap<>();
        private long nowMillis;
        private int staleWriteRejections;
        private boolean failReads;

        @Override
        public synchronized Optional<CanonicalToken> find(String scope) {
            if (failReads) {
                throw new TossApiException("Toss token Redis canonical 조회 실패", null);
            }
            CanonicalEntry entry = canonical.get(scope);
            if (entry == null || entry.expiresAtMillis() <= nowMillis) {
                canonical.remove(scope);
                return Optional.empty();
            }
            return Optional.of(new CanonicalToken(
                    entry.accessToken(), entry.generation(), entry.expiresAtMillis()));
        }

        @Override
        public synchronized Optional<Lease> tryAcquire(
                String scope, String ownerId, Duration leaseTtl) {
            LeaseEntry current = liveLease(scope);
            if (current != null) {
                return Optional.empty();
            }
            long generation = generations.merge(scope, 1L, Long::sum);
            leases.put(scope, new LeaseEntry(ownerId, generation, nowMillis + leaseTtl.toMillis()));
            return Optional.of(new Lease(scope, ownerId, generation));
        }

        @Override
        public synchronized StoreResult storeIfCurrent(
                Lease lease, TokenValue token, Duration canonicalTtl) {
            long latestGeneration = generations.getOrDefault(lease.scope(), 0L);
            CanonicalEntry current = canonical.get(lease.scope());
            long canonicalGeneration = current == null ? 0L : current.generation();
            if (lease.generation() < latestGeneration || lease.generation() < canonicalGeneration) {
                staleWriteRejections++;
                return StoreResult.STALE;
            }
            long expiresAt = nowMillis + canonicalTtl.toMillis();
            canonical.put(lease.scope(), new CanonicalEntry(
                    token.accessToken(), lease.generation(), expiresAt));
            fingerprints.put(lease.scope(), new FingerprintEntry(
                    TossRedisTokenStore.fingerprint(token.accessToken()), nowMillis + 2_000));
            return StoreResult.STORED;
        }

        @Override
        public synchronized boolean matchesRecentFingerprint(String scope, String token) {
            FingerprintEntry entry = fingerprints.get(scope);
            if (entry == null || entry.expiresAtMillis() <= nowMillis) {
                fingerprints.remove(scope);
                return false;
            }
            return Objects.equals(entry.value(), TossRedisTokenStore.fingerprint(token));
        }

        @Override
        public synchronized void release(Lease lease) {
            LeaseEntry current = liveLease(lease.scope());
            if (current != null && Objects.equals(current.ownerId(), lease.ownerId())) {
                leases.remove(lease.scope());
            }
        }

        synchronized void advance(Duration duration) {
            nowMillis += duration.toMillis();
        }

        synchronized Duration ttl(String scope) {
            CanonicalEntry entry = canonical.get(scope);
            return entry == null ? Duration.ZERO : Duration.ofMillis(entry.expiresAtMillis() - nowMillis);
        }

        synchronized String fingerprint(String scope) {
            FingerprintEntry entry = fingerprints.get(scope);
            return entry == null ? null : entry.value();
        }

        synchronized int staleWriteRejections() {
            return staleWriteRejections;
        }

        synchronized void failReads() {
            failReads = true;
        }

        private LeaseEntry liveLease(String scope) {
            LeaseEntry current = leases.get(scope);
            if (current != null && current.expiresAtMillis() <= nowMillis) {
                leases.remove(scope);
                return null;
            }
            return current;
        }

        private record CanonicalEntry(String accessToken, long generation, long expiresAtMillis) {}
        private record LeaseEntry(String ownerId, long generation, long expiresAtMillis) {}
        private record FingerprintEntry(String value, long expiresAtMillis) {}
    }
}
