package com.kista.user.adapter.out.redis;

import com.kista.platform.security.RedisTokenBlacklistReader;
import com.kista.user.application.port.output.BlacklistPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

// 읽기 3종은 platform 공용 베이스(RedisTokenBlacklistReader)가 담당 — 이 어댑터는 쓰기 3종만 추가한다
@Component
class RedisBlacklistAdapter extends RedisTokenBlacklistReader implements BlacklistPort {

    RedisBlacklistAdapter(StringRedisTemplate redisTemplate) {
        super(redisTemplate);
    }

    @Override
    public void add(UUID userId, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, "1", ttl);
    }

    @Override
    public void addJti(String jti, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX_JTI + jti, "1", ttl);
    }

    @Override
    public void markRoleChanged(UUID userId, Instant changedAt, Duration ttl) {
        redisTemplate.opsForValue().set(KEY_PREFIX_ROLE + userId, String.valueOf(changedAt.getEpochSecond()), ttl);
    }
}
