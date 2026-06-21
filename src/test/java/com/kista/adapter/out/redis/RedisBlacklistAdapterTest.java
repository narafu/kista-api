package com.kista.adapter.out.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisBlacklistAdapterTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks RedisBlacklistAdapter adapter;

    @Test
    void add_setsKeyWithTtl() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        adapter.add(userId, Duration.ofMinutes(15));

        verify(valueOps).set("blacklist:user:" + userId, "1", Duration.ofMinutes(15));
    }

    @Test
    void isBlacklisted_keyExists_returnsTrue() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(true);

        assertThat(adapter.isBlacklisted(userId)).isTrue();
    }

    @Test
    void isBlacklisted_keyAbsent_returnsFalse() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(false);

        assertThat(adapter.isBlacklisted(userId)).isFalse();
    }

    @Test
    void isBlacklisted_nullFromRedis_returnsFalse() {
        UUID userId = UUID.randomUUID();
        given(redisTemplate.hasKey("blacklist:user:" + userId)).willReturn(null);

        assertThat(adapter.isBlacklisted(userId)).isFalse();
    }

    @Test
    void addJti_setsKeyWithTtl() {
        String jti = "test-jti-value";
        given(redisTemplate.opsForValue()).willReturn(valueOps);

        adapter.addJti(jti, Duration.ofDays(1));

        verify(valueOps).set("blacklist:jti:" + jti, "1", Duration.ofDays(1));
    }

    @Test
    void isJtiBlacklisted_keyExists_returnsTrue() {
        String jti = "test-jti-value";
        given(redisTemplate.hasKey("blacklist:jti:" + jti)).willReturn(true);

        assertThat(adapter.isJtiBlacklisted(jti)).isTrue();
    }

    @Test
    void isJtiBlacklisted_keyAbsent_returnsFalse() {
        String jti = "test-jti-value";
        given(redisTemplate.hasKey("blacklist:jti:" + jti)).willReturn(false);

        assertThat(adapter.isJtiBlacklisted(jti)).isFalse();
    }

    @Test
    void isJtiBlacklisted_nullFromRedis_returnsFalse() {
        String jti = "test-jti-value";
        given(redisTemplate.hasKey("blacklist:jti:" + jti)).willReturn(null);

        assertThat(adapter.isJtiBlacklisted(jti)).isFalse();
    }
}
