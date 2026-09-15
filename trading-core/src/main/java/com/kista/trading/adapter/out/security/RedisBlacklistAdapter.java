package com.kista.trading.adapter.out.security;

import com.kista.platform.security.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

// root(com.kista.user.adapter.out.redis.RedisBlacklistAdapter)와 같은 Redis 인스턴스·같은 키 네임스페이스를
// 읽기 전용으로 조회 — 블랙리스트 등록(add/addJti/markRoleChanged)은 root의 로그아웃/강퇴 흐름 전용이라
// trading-core는 쓰지 않는다.
@Component("tradingRedisBlacklistAdapter")
@RequiredArgsConstructor
class RedisBlacklistAdapter implements TokenBlacklistPort {

    private static final String KEY_PREFIX = "blacklist:user:";
    private static final String KEY_PREFIX_JTI = "blacklist:jti:";
    private static final String KEY_PREFIX_ROLE = "blacklist:rolechange:";
    private final StringRedisTemplate redisTemplate;

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
