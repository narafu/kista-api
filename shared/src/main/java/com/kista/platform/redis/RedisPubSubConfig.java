package com.kista.platform.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

// 두 bootJar(root/trading-core)가 같은 채널명 문자열을 쓰도록 강제하는 상수 홀더 — 오타로 인한
// 발행/구독 채널 불일치를 컴파일 타임에 방지. RedisMessageListenerContainer 공통 빈도 여기서 배선한다 —
// 실제 구독은 root(TradeSseEmitterRegistry/PushNotificationRelayListener)만 하지만, 두 앱 모두
// com.kista.platform을 스캔하므로 :shared에 두면 별도 배선 없이 재사용된다(trading-core에서는 미사용 빈).
@Configuration
public class RedisPubSubConfig {

    public static final String TRADE_EVENT_CHANNEL = "trade.event";
    public static final String PUSH_NOTIFICATION_CHANNEL = "user.push-notification.requested";

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
