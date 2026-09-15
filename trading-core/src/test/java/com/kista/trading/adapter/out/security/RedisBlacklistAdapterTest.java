package com.kista.trading.adapter.out.security;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RedisBlacklistAdapterTest {

    private final StringRedisTemplate redisTemplate = Mockito.mock(StringRedisTemplate.class);
    private final RedisBlacklistAdapter adapter = new RedisBlacklistAdapter(redisTemplate);

    @Test
    void isBlacklisted_root와_동일_키_네임스페이스_사용() {
        UUID userId = UUID.randomUUID();
        when(redisTemplate.hasKey("blacklist:user:" + userId)).thenReturn(true);

        assertThat(adapter.isBlacklisted(userId)).isTrue();
    }

    @Test
    void roleChangedAt_값_없으면_null() {
        UUID userId = UUID.randomUUID();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blacklist:rolechange:" + userId)).thenReturn(null);

        assertThat(adapter.roleChangedAt(userId)).isNull();
    }

    @Test
    void roleChangedAt_epoch_seconds_파싱() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get("blacklist:rolechange:" + userId)).thenReturn(String.valueOf(now.getEpochSecond()));

        assertThat(adapter.roleChangedAt(userId)).isEqualTo(Instant.ofEpochSecond(now.getEpochSecond()));
    }
}
