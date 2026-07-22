package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TossDistributedTokenCoordinator {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration RECENT_FINGERPRINT_TTL = Duration.ofSeconds(2);
    private static final Duration ADMIN_EXPIRY_MARGIN = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 320;
    private static final String REDIS_KEY_PREFIX = "toss:token:";
    private static final String LOCK_SCOPE_PREFIX = "kista:toss-token:";

    static final String ADMIN_TOKEN_KEY = REDIS_KEY_PREFIX + "admin";
    static final String ADMIN_FINGERPRINT_KEY = REDIS_KEY_PREFIX + "recent:admin";
    static final long ADMIN_LOCK_KEY = lockKey(LOCK_SCOPE_PREFIX + "admin");

    private final StringRedisTemplate redisTemplate;
    private final TossTokenIssuanceLock issuanceLock;
    private final PollWaiter pollWaiter;
    private final int maxPollAttempts;

    @Autowired
    TossDistributedTokenCoordinator(
            StringRedisTemplate redisTemplate,
            TossTokenIssuanceLock issuanceLock
    ) {
        this(redisTemplate, issuanceLock, TossDistributedTokenCoordinator::sleep,
                DEFAULT_MAX_POLL_ATTEMPTS);
    }

    TossDistributedTokenCoordinator(
            StringRedisTemplate redisTemplate,
            TossTokenIssuanceLock issuanceLock,
            PollWaiter pollWaiter,
            int maxPollAttempts
    ) {
        this.redisTemplate = redisTemplate;
        this.issuanceLock = issuanceLock;
        this.pollWaiter = pollWaiter;
        this.maxPollAttempts = maxPollAttempts;
    }

    String getAccountToken(
            UUID accountId,
            Supplier<Optional<String>> currentToken,
            AccountTokenIssuer issuer,
            AccountTokenPersister persister
    ) {
        Optional<String> cached = currentToken.get();
        if (cached.isPresent()) {
            return cached.get();
        }
        return coordinateAccountIssuance(
                accountId, null, currentToken, ignored -> {}, issuer, persister);
    }

    String recoverAccountToken(
            UUID accountId,
            String rejectedToken,
            Supplier<Optional<String>> currentToken,
            Consumer<String> invalidator,
            AccountTokenIssuer issuer,
            AccountTokenPersister persister
    ) {
        Optional<String> current = currentToken.get();
        if (isNewer(current, rejectedToken)) {
            return current.orElseThrow();
        }
        if (matchesRecentFingerprint(accountFingerprintKey(accountId), rejectedToken)) {
            return rejectedToken;
        }
        return coordinateAccountIssuance(
                accountId, rejectedToken, currentToken, invalidator, issuer, persister);
    }

    String getAdminToken(AdminTokenIssuer issuer) {
        String cached = redisGet(ADMIN_TOKEN_KEY);
        if (cached != null) {
            return cached;
        }
        return coordinateAdminIssuance(null, issuer);
    }

    String recoverAdminToken(String rejectedToken, AdminTokenIssuer issuer) {
        String current = redisGet(ADMIN_TOKEN_KEY);
        if (current != null && !Objects.equals(current, rejectedToken)) {
            return current;
        }
        if (matchesRecentFingerprint(ADMIN_FINGERPRINT_KEY, rejectedToken)) {
            return rejectedToken;
        }
        return coordinateAdminIssuance(rejectedToken, issuer);
    }

    private String coordinateAccountIssuance(
            UUID accountId,
            String rejectedToken,
            Supplier<Optional<String>> currentToken,
            Consumer<String> invalidator,
            AccountTokenIssuer issuer,
            AccountTokenPersister persister
    ) {
        long lockKey = accountLockKey(accountId);
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenIssuanceLock.Handle> acquired = issuanceLock.tryAcquire(lockKey);
            if (acquired.isPresent()) {
                try (TossTokenIssuanceLock.Handle ignored = acquired.orElseThrow()) {
                    Optional<String> current = currentToken.get();
                    if (rejectedToken == null && current.isPresent()) {
                        return current.get();
                    }
                    if (rejectedToken != null) {
                        if (isNewer(current, rejectedToken)) {
                            return current.orElseThrow();
                        }
                        if (matchesRecentFingerprint(accountFingerprintKey(accountId), rejectedToken)) {
                            return rejectedToken;
                        }
                        invalidator.accept(rejectedToken);
                    }
                    IssuedAccountToken issuedToken = issuer.issue();
                    // Redis 세대 보호가 실패한 fresh token을 DB에 commit하지 않도록
                    // fingerprint를 canonical persistence보다 먼저 저장한다.
                    storeFingerprint(accountFingerprintKey(accountId), issuedToken.accessToken());
                    persister.persist(issuedToken);
                    return issuedToken.accessToken();
                }
            }

            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
            Optional<String> current = currentToken.get();
            if (rejectedToken == null && current.isPresent()) {
                return current.get();
            }
            if (rejectedToken != null) {
                if (isNewer(current, rejectedToken)) {
                    return current.orElseThrow();
                }
                if (matchesRecentFingerprint(accountFingerprintKey(accountId), rejectedToken)) {
                    return rejectedToken;
                }
            }
        }
        throw waitTimeout(lockKey);
    }

    private String coordinateAdminIssuance(String rejectedToken, AdminTokenIssuer issuer) {
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            Optional<TossTokenIssuanceLock.Handle> acquired = issuanceLock.tryAcquire(ADMIN_LOCK_KEY);
            if (acquired.isPresent()) {
                try (TossTokenIssuanceLock.Handle ignored = acquired.orElseThrow()) {
                    String current = redisGet(ADMIN_TOKEN_KEY);
                    if (rejectedToken == null && current != null) {
                        return current;
                    }
                    if (rejectedToken != null) {
                        if (current != null && !Objects.equals(current, rejectedToken)) {
                            return current;
                        }
                        if (matchesRecentFingerprint(ADMIN_FINGERPRINT_KEY, rejectedToken)) {
                            return rejectedToken;
                        }
                    }
                    IssuedAdminToken issuedToken = issuer.issue();
                    storeFingerprint(ADMIN_FINGERPRINT_KEY, issuedToken.accessToken());
                    storeAdminToken(issuedToken);
                    return issuedToken.accessToken();
                }
            }

            if (attempt == maxPollAttempts) {
                break;
            }
            pollWaiter.await(POLL_INTERVAL);
            String current = redisGet(ADMIN_TOKEN_KEY);
            if (rejectedToken == null && current != null) {
                return current;
            }
            if (rejectedToken != null) {
                if (current != null && !Objects.equals(current, rejectedToken)) {
                    return current;
                }
                if (matchesRecentFingerprint(ADMIN_FINGERPRINT_KEY, rejectedToken)) {
                    return rejectedToken;
                }
            }
        }
        throw waitTimeout(ADMIN_LOCK_KEY);
    }

    private String redisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            throw redisFailure("캐시 조회", exception);
        }
    }

    private void redisSet(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException exception) {
            throw redisFailure("캐시 저장", exception);
        }
    }

    private void storeAdminToken(IssuedAdminToken issuedToken) {
        Duration ttl = Duration.ofSeconds(issuedToken.expiresInSeconds()).minus(ADMIN_EXPIRY_MARGIN);
        if (ttl.isZero() || ttl.isNegative()) {
            throw new TossApiException("Toss 관리자 토큰 만료 시간이 5분 이하입니다", null);
        }
        redisSet(ADMIN_TOKEN_KEY, issuedToken.accessToken(), ttl);
    }

    private void storeFingerprint(String key, String token) {
        redisSet(key, fingerprint(token), RECENT_FINGERPRINT_TTL);
    }

    private boolean matchesRecentFingerprint(String key, String token) {
        return token != null && Objects.equals(redisGet(key), fingerprint(token));
    }

    private static boolean isNewer(Optional<String> currentToken, String rejectedToken) {
        return currentToken.isPresent() && !Objects.equals(currentToken.get(), rejectedToken);
    }

    private static String fingerprint(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    static long accountLockKey(UUID accountId) {
        return lockKey(LOCK_SCOPE_PREFIX + "account:" + accountId);
    }

    static String accountFingerprintKey(UUID accountId) {
        return REDIS_KEY_PREFIX + "recent:account:" + accountId;
    }

    private static long lockKey(String scope) {
        return ByteBuffer.wrap(sha256(scope)).getLong();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }

    private static TossApiException redisFailure(String operation, RuntimeException cause) {
        return new TossApiException("Toss 토큰 Redis " + operation + " 실패", cause);
    }

    private static TossApiException waitTimeout(long lockKey) {
        return new TossApiException(
                "Toss 토큰 PostgreSQL advisory lock 대기 시간 초과: " + lockKey, null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TossApiException(
                    "Toss 토큰 PostgreSQL advisory lock 대기 중 interrupted", exception);
        }
    }

    @FunctionalInterface
    interface PollWaiter {
        void await(Duration duration);
    }

    @FunctionalInterface
    interface AccountTokenIssuer {
        IssuedAccountToken issue();
    }

    @FunctionalInterface
    interface AccountTokenPersister {
        void persist(IssuedAccountToken issuedToken);
    }

    @FunctionalInterface
    interface AdminTokenIssuer {
        IssuedAdminToken issue();
    }

    record IssuedAccountToken(String accessToken, OffsetDateTime expiresAt) {}

    record IssuedAdminToken(String accessToken, long expiresInSeconds) {}
}
