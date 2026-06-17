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

    private static final String KEY_PREFIX = "blacklist:user:";
    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", ttl);
    }

    @Override
    public boolean isBlacklisted(UUID userId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + userId));
    }
}
