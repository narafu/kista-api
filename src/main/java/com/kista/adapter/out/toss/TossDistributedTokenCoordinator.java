package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
class TossDistributedTokenCoordinator {

    private static final String ADMIN_SCOPE = "admin";
    private static final Duration LEASE_TTL = Duration.ofMinutes(1);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration TOKEN_EXPIRY_MARGIN = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 320;

    private final TossTokenStore tokenStore;
    private final PollWaiter pollWaiter;
    private final Supplier<String> ownerIds;
    private final int maxPollAttempts;

    @Autowired
    TossDistributedTokenCoordinator(TossTokenStore tokenStore) {
        this(tokenStore, TossDistributedTokenCoordinator::sleep,
                () -> UUID.randomUUID().toString(), DEFAULT_MAX_POLL_ATTEMPTS);
    }

    TossDistributedTokenCoordinator(
            TossTokenStore tokenStore,
            PollWaiter pollWaiter,
            Supplier<String> ownerIds,
            int maxPollAttempts
    ) {
        this.tokenStore = tokenStore;
        this.pollWaiter = pollWaiter;
        this.ownerIds = ownerIds;
        this.maxPollAttempts = maxPollAttempts;
    }

    String getAccountToken(UUID accountId, TokenIssuer issuer) {
        return getOrIssue(accountScope(accountId), null, issuer);
    }

    String recoverAccountToken(UUID accountId, String rejectedToken, TokenIssuer issuer) {
        return getOrIssue(accountScope(accountId), rejectedToken, issuer);
    }

    String getAdminToken(TokenIssuer issuer) {
        return getOrIssue(ADMIN_SCOPE, null, issuer);
    }

    String recoverAdminToken(String rejectedToken, TokenIssuer issuer) {
        return getOrIssue(ADMIN_SCOPE, rejectedToken, issuer);
    }

    private String getOrIssue(String scope, String rejectedToken, TokenIssuer issuer) {
        Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(scope);
        String reusable = reusableToken(scope, current, rejectedToken);
        if (reusable != null) {
            return reusable;
        }

        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenStore.Lease> lease = tokenStore.tryAcquire(
                    scope, ownerIds.get(), LEASE_TTL);
            if (lease.isPresent()) {
                return issueAsOwner(lease.orElseThrow(), rejectedToken, issuer);
            }
            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
            current = tokenStore.find(scope);
            reusable = reusableToken(scope, current, rejectedToken);
            if (reusable != null) {
                return reusable;
            }
        }
        throw new TossApiException("Toss token Redis lease 대기 시간 초과: " + scope, null);
    }

    private String issueAsOwner(
            TossTokenStore.Lease lease,
            String rejectedToken,
            TokenIssuer issuer
    ) {
        try {
            Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(lease.scope());
            String reusable = reusableToken(lease.scope(), current, rejectedToken);
            if (reusable != null) {
                return reusable;
            }

            TossTokenStore.TokenValue issued = issuer.issue();
            Duration canonicalTtl = Duration.ofSeconds(issued.expiresInSeconds())
                    .minus(TOKEN_EXPIRY_MARGIN);
            if (canonicalTtl.isZero() || canonicalTtl.isNegative()) {
                throw new TossApiException("Toss token 만료 시간이 5분 이하입니다", null);
            }
            TossTokenStore.StoreResult result = tokenStore.storeIfCurrent(
                    lease, issued, canonicalTtl);
            if (result == TossTokenStore.StoreResult.STORED) {
                return issued.accessToken();
            }
            return awaitNewerCanonical(lease.scope(), lease.generation());
        } finally {
            tokenStore.release(lease);
        }
    }

    private String awaitNewerCanonical(String scope, long staleGeneration) {
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenStore.CanonicalToken> current = tokenStore.find(scope);
            if (current.isPresent() && current.orElseThrow().generation() > staleGeneration) {
                return current.orElseThrow().accessToken();
            }
            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
        }
        throw new TossApiException(
                "Toss token newer fencing generation 대기 시간 초과: " + scope, null);
    }

    private String reusableToken(
            String scope,
            Optional<TossTokenStore.CanonicalToken> current,
            String rejectedToken
    ) {
        if (rejectedToken == null) {
            return current.map(TossTokenStore.CanonicalToken::accessToken).orElse(null);
        }
        if (current.isPresent()
                && !Objects.equals(current.orElseThrow().accessToken(), rejectedToken)) {
            return current.orElseThrow().accessToken();
        }
        return tokenStore.matchesRecentFingerprint(scope, rejectedToken) ? rejectedToken : null;
    }

    static String accountScope(UUID accountId) {
        return "account:" + accountId;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TossApiException("Toss token Redis lease 대기 중 interrupted", exception);
        }
    }

    @FunctionalInterface
    interface PollWaiter {
        void await(Duration duration);
    }

    @FunctionalInterface
    interface TokenIssuer {
        TossTokenStore.TokenValue issue();
    }
}
