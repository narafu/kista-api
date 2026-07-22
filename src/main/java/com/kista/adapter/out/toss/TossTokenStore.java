package com.kista.adapter.out.toss;

import java.time.Duration;
import java.util.Optional;

interface TossTokenStore {

    Optional<CanonicalToken> find(String scope);

    Optional<Lease> tryAcquire(String scope, String ownerId, Duration leaseTtl);

    StoreResult storeIfCurrent(Lease lease, TokenValue token, Duration canonicalTtl);

    boolean matchesRecentFingerprint(String scope, String token);

    void release(Lease lease);

    record CanonicalToken(String accessToken, long generation, long expiresAtEpochMillis) {}

    record Lease(String scope, String ownerId, long generation) {}

    record TokenValue(String accessToken, long expiresInSeconds) {}

    enum StoreResult {
        STORED,
        STALE
    }
}
