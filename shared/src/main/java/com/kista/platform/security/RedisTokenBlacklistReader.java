package com.kista.platform.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

// 토큰 블랙리스트 Redis 읽기 공용 구현(비-빈 베이스) — root(user)와 trading-core가 같은 Redis 키 네임스페이스를 공유한다.
// 빈으로 등록하지 않는다: TokenBlacklistPort 빈이 프로세스당 정확히 1개여야 JwtAuthFilter 주입이 모호해지지 않는다
// (root는 user.RedisBlacklistAdapter, trading-core는 trading.RedisBlacklistAdapter가 각각 서브클래스 빈)
public abstract class RedisTokenBlacklistReader implements TokenBlacklistPort {

    protected static final String KEY_PREFIX = "blacklist:user:";          // userId 단위 블랙리스트 키 접두사
    protected static final String KEY_PREFIX_JTI = "blacklist:jti:";       // jti 단위 블랙리스트 키 접두사
    protected static final String KEY_PREFIX_ROLE = "blacklist:rolechange:"; // role 변경 시각 키 접두사

    protected final StringRedisTemplate redisTemplate; // 서브클래스의 쓰기 메서드도 공유

    protected RedisTokenBlacklistReader(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isBlacklisted(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }

    @Override
    public boolean isJtiBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX_JTI + jti));
    }

    @Override
    public Instant roleChangedAt(UUID userId) {
        String v = redisTemplate.opsForValue().get(KEY_PREFIX_ROLE + userId);
        return v == null ? null : Instant.ofEpochSecond(Long.parseLong(v));
    }
}
