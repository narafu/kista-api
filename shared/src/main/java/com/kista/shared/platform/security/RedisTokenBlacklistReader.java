package com.kista.shared.platform.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.UUID;

// 토큰 블랙리스트 Redis 읽기 공용 구현 — root(user)와 trading-core가 같은 Redis 키 네임스페이스를 공유한다.
// TokenBlacklistPort 빈은 프로세스당 정확히 1개여야 JwtAuthFilter 주입이 모호해지지 않는다 — 그래서 PlatformAutoConfiguration이
// 앱에 TokenBlacklistPort 빈이 없을 때만(trading-core) 이 클래스를 빈으로 올리고, root는 쓰기를 더한 서브클래스 user.RedisBlacklistAdapter를 쓴다
public class RedisTokenBlacklistReader implements TokenBlacklistPort {

    protected static final String KEY_PREFIX = "blacklist:user:";          // userId 단위 블랙리스트 키 접두사
    protected static final String KEY_PREFIX_JTI = "blacklist:jti:";       // jti 단위 블랙리스트 키 접두사
    protected static final String KEY_PREFIX_ROLE = "blacklist:rolechange:"; // role 변경 시각 키 접두사

    protected final StringRedisTemplate redisTemplate; // 서브클래스의 쓰기 메서드도 공유

    public RedisTokenBlacklistReader(StringRedisTemplate redisTemplate) {
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
