package com.kista.adapter.out.redis;

import com.kista.domain.port.out.BlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class RedisBlacklistAdapter implements BlacklistPort {

    private static final String KEY_PREFIX = "blacklist:user:"; // userId 단위 블랙리스트 키 접두사
    private static final String KEY_PREFIX_JTI = "blacklist:jti:"; // jti 단위 블랙리스트 키 접두사
    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", ttl);
    }

    @Override
    public boolean isBlacklisted(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }

    @Override
    public void addJti(String jti, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX_JTI + jti, "1", ttl);
    }

    @Override
    public boolean isJtiBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX_JTI + jti));
    }
}
