package com.kista.trading.adapter.out.security;

import com.kista.platform.security.RedisTokenBlacklistReader;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// root(user.RedisBlacklistAdapter)와 같은 Redis 키를 platform 공용 읽기 구현으로 조회 — 등록(add/addJti/markRoleChanged)은 root 전용
@Component("tradingRedisBlacklistAdapter")
class RedisBlacklistAdapter extends RedisTokenBlacklistReader {

    RedisBlacklistAdapter(StringRedisTemplate redisTemplate) {
        super(redisTemplate);
    }
}
