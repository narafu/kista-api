package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
class TossDistributedTokenCoordinator {

    // Toss HTTP 최대 13초(connect 3s + response 10s) + Hikari 연결 대기 20초보다 충분히 길게 잡아
    // 정상 발급·DB 저장 중 lease 만료로 다른 인스턴스가 중복 발급하지 않게 한다.
    private static final Duration LEASE_TTL = Duration.ofMinutes(1);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final Duration RECENT_FINGERPRINT_TTL = Duration.ofSeconds(2);
    private static final Duration ADMIN_EXPIRY_MARGIN = Duration.ofMinutes(5);
    private static final int DEFAULT_MAX_POLL_ATTEMPTS = 320;
    private static final String KEY_PREFIX = "toss:token:";

    static final String ADMIN_TOKEN_KEY = KEY_PREFIX + "admin";
    static final String ADMIN_LEASE_KEY = KEY_PREFIX + "lease:admin";
    static final String ADMIN_FINGERPRINT_KEY = KEY_PREFIX + "recent:admin";

    private static final DefaultRedisScript<Long> RELEASE_LEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final PollWaiter pollWaiter;
    private final Supplier<String> ownerIds;
    private final int maxPollAttempts;

    @Autowired
    TossDistributedTokenCoordinator(StringRedisTemplate redisTemplate) {
        this(redisTemplate, TossDistributedTokenCoordinator::sleep,
                () -> UUID.randomUUID().toString(), DEFAULT_MAX_POLL_ATTEMPTS);
    }

    TossDistributedTokenCoordinator(
            StringRedisTemplate redisTemplate,
            PollWaiter pollWaiter,
            Supplier<String> ownerIds,
            int maxPollAttempts
    ) {
        this.redisTemplate = redisTemplate;
        this.pollWaiter = pollWaiter;
        this.ownerIds = ownerIds;
        this.maxPollAttempts = maxPollAttempts;
    }

    String getAccountToken(
            UUID accountId,
            Supplier<Optional<String>> currentToken,
            AccountTokenIssuer issuer
    ) {
        Optional<String> cached = currentToken.get();
        if (cached.isPresent()) {
            return cached.get();
        }
        return coordinateAccountIssuance(accountId, null, currentToken, ignored -> {}, issuer);
    }

    String recoverAccountToken(
            UUID accountId,
            String rejectedToken,
            Supplier<Optional<String>> currentToken,
            Consumer<String> invalidator,
            AccountTokenIssuer issuer
    ) {
        Optional<String> current = currentToken.get();
        if (isNewer(current, rejectedToken)) {
            return current.orElseThrow();
        }
        if (matchesRecentFingerprint(accountFingerprintKey(accountId), rejectedToken)) {
            return rejectedToken;
        }
        return coordinateAccountIssuance(accountId, rejectedToken, currentToken, invalidator, issuer);
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
            AccountTokenIssuer issuer
    ) {
        String leaseKey = accountLeaseKey(accountId);
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            String ownerId = ownerIds.get();
            if (acquireLease(leaseKey, ownerId)) {
                try {
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
                    String issuedToken = issuer.issue();
                    storeFingerprint(accountFingerprintKey(accountId), issuedToken);
                    return issuedToken;
                } finally {
                    releaseLease(leaseKey, ownerId);
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
        throw waitTimeout(leaseKey);
    }

    private String coordinateAdminIssuance(String rejectedToken, AdminTokenIssuer issuer) {
        for (int attempt = 0; attempt <= maxPollAttempts; attempt++) {
            String ownerId = ownerIds.get();
            if (acquireLease(ADMIN_LEASE_KEY, ownerId)) {
                try {
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
                    storeAdminToken(issuedToken);
                    storeFingerprint(ADMIN_FINGERPRINT_KEY, issuedToken.accessToken());
                    return issuedToken.accessToken();
                } finally {
                    releaseLease(ADMIN_LEASE_KEY, ownerId);
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
        throw waitTimeout(ADMIN_LEASE_KEY);
    }

    private boolean acquireLease(String leaseKey, String ownerId) {
        try {
            return Boolean.TRUE.equals(
                    redisTemplate.opsForValue().setIfAbsent(leaseKey, ownerId, LEASE_TTL));
        } catch (RuntimeException exception) {
            throw redisFailure("lease 획득", exception);
        }
    }

    private void releaseLease(String leaseKey, String ownerId) {
        try {
            redisTemplate.execute(RELEASE_LEASE_SCRIPT, List.of(leaseKey), ownerId);
        } catch (RuntimeException exception) {
            throw redisFailure("lease 해제", exception);
        }
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }

    static String accountLeaseKey(UUID accountId) {
        return KEY_PREFIX + "lease:account:" + accountId;
    }

    static String accountFingerprintKey(UUID accountId) {
        return KEY_PREFIX + "recent:account:" + accountId;
    }

    private static TossApiException redisFailure(String operation, RuntimeException cause) {
        return new TossApiException("Toss 토큰 Redis " + operation + " 실패", cause);
    }

    private static TossApiException waitTimeout(String leaseKey) {
        return new TossApiException("Toss 토큰 발급 lease 대기 시간 초과: " + leaseKey, null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TossApiException("Toss 토큰 발급 lease 대기 중 interrupted", exception);
        }
    }

    @FunctionalInterface
    interface PollWaiter {
        void await(Duration duration);
    }

    @FunctionalInterface
    interface AccountTokenIssuer {
        String issue();
    }

    @FunctionalInterface
    interface AdminTokenIssuer {
        IssuedAdminToken issue();
    }

    record IssuedAdminToken(String accessToken, long expiresInSeconds) {}
}
