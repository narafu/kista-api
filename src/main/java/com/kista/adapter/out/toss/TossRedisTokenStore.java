package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossApiException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
class TossRedisTokenStore implements TossTokenStore {

    private static final String KEY_PREFIX = "toss:token:";
    private static final String FIELD_ACCESS_TOKEN = "access_token";
    private static final String FIELD_GENERATION = "generation";
    private static final String FIELD_EXPIRES_AT = "expires_at_epoch_ms";
    private static final Duration RECENT_FINGERPRINT_TTL = Duration.ofSeconds(2);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local generation = redis.call('incr', KEYS[2]) "
                    + "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]) "
                    + "return generation",
            Long.class);

    private static final DefaultRedisScript<Long> STORE_SCRIPT = new DefaultRedisScript<>(
            "local incoming = tonumber(ARGV[1]) "
                    + "local latest = tonumber(redis.call('get', KEYS[2]) or '0') "
                    + "local stored = tonumber(redis.call('hget', KEYS[1], 'generation') or '0') "
                    + "if incoming < latest or incoming < stored then return 0 end "
                    + "redis.call('hset', KEYS[1], "
                    + "'access_token', ARGV[2], 'generation', ARGV[1], "
                    + "'expires_at_epoch_ms', ARGV[3]) "
                    + "redis.call('pexpire', KEYS[1], ARGV[4]) "
                    + "redis.call('psetex', KEYS[3], ARGV[6], ARGV[5]) "
                    + "return 1",
            Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] "
                    + "then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    TossRedisTokenStore(StringRedisTemplate redisTemplate) {
        this(redisTemplate, Clock.systemUTC());
    }

    TossRedisTokenStore(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    @Override
    public Optional<CanonicalToken> find(String scope) {
        try {
            HashOperations<String, String, String> hashes = redisTemplate.opsForHash();
            Map<String, String> fields = hashes.entries(canonicalKey(scope));
            if (fields.isEmpty()) {
                return Optional.empty();
            }
            String accessToken = fields.get(FIELD_ACCESS_TOKEN);
            long generation = parseRequiredLong(fields, FIELD_GENERATION, scope);
            long expiresAt = parseRequiredLong(fields, FIELD_EXPIRES_AT, scope);
            if (accessToken == null || accessToken.isBlank()) {
                throw malformed(scope, "access token 누락");
            }
            if (expiresAt <= clock.millis()) {
                return Optional.empty();
            }
            return Optional.of(new CanonicalToken(accessToken, generation, expiresAt));
        } catch (TossApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw redisFailure("canonical 조회", exception);
        }
    }

    @Override
    public Optional<Lease> tryAcquire(String scope, String ownerId, Duration leaseTtl) {
        try {
            Long generation = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(leaseKey(scope), generationKey(scope)),
                    ownerId,
                    String.valueOf(leaseTtl.toMillis()));
            if (generation == null) {
                throw redisFailure("lease 획득", new IllegalStateException("null script result"));
            }
            return generation == 0L
                    ? Optional.empty()
                    : Optional.of(new Lease(scope, ownerId, generation));
        } catch (TossApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw redisFailure("lease 획득", exception);
        }
    }

    @Override
    public StoreResult storeIfCurrent(Lease lease, TokenValue token, Duration canonicalTtl) {
        long expiresAt = clock.millis() + canonicalTtl.toMillis();
        try {
            Long stored = redisTemplate.execute(
                    STORE_SCRIPT,
                    List.of(
                            canonicalKey(lease.scope()),
                            generationKey(lease.scope()),
                            fingerprintKey(lease.scope())),
                    String.valueOf(lease.generation()),
                    token.accessToken(),
                    String.valueOf(expiresAt),
                    String.valueOf(canonicalTtl.toMillis()),
                    fingerprint(token.accessToken()),
                    String.valueOf(RECENT_FINGERPRINT_TTL.toMillis()));
            if (stored == null) {
                throw redisFailure("canonical 저장", new IllegalStateException("null script result"));
            }
            return stored == 1L ? StoreResult.STORED : StoreResult.STALE;
        } catch (TossApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw redisFailure("canonical 저장", exception);
        }
    }

    @Override
    public boolean matchesRecentFingerprint(String scope, String token) {
        if (token == null) {
            return false;
        }
        try {
            return Objects.equals(
                    redisTemplate.opsForValue().get(fingerprintKey(scope)),
                    fingerprint(token));
        } catch (RuntimeException exception) {
            throw redisFailure("fingerprint 조회", exception);
        }
    }

    @Override
    public void release(Lease lease) {
        try {
            redisTemplate.execute(
                    RELEASE_SCRIPT,
                    List.of(leaseKey(lease.scope())),
                    lease.ownerId());
        } catch (RuntimeException exception) {
            throw redisFailure("lease 해제", exception);
        }
    }

    static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest unavailable", exception);
        }
    }

    static String canonicalKey(String scope) {
        return KEY_PREFIX + "canonical:" + scope;
    }

    static String leaseKey(String scope) {
        return KEY_PREFIX + "lease:" + scope;
    }

    static String generationKey(String scope) {
        return KEY_PREFIX + "generation:" + scope;
    }

    static String fingerprintKey(String scope) {
        return KEY_PREFIX + "recent:" + scope;
    }

    private static long parseRequiredLong(Map<String, String> fields, String field, String scope) {
        String value = fields.get(field);
        if (value == null) {
            throw malformed(scope, field + " 누락");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw malformed(scope, field + " 형식 오류", exception);
        }
    }

    private static TossApiException malformed(String scope, String detail) {
        return malformed(scope, detail, null);
    }

    private static TossApiException malformed(String scope, String detail, Throwable cause) {
        return new TossApiException(
                "Toss token Redis canonical 상태 오류: scope=" + scope + ", " + detail,
                cause);
    }

    private static TossApiException redisFailure(String operation, RuntimeException cause) {
        return new TossApiException("Toss token Redis " + operation + " 실패", cause);
    }
}
