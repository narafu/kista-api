package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// stream:user.deleted / stream:user.notify-profile.changed 컨슈머 그룹 구독 배선.
// 컨슈머 이름은 인스턴스마다 고유해야 pending 추적이 꼬이지 않으므로 프로세스 시작 시 UUID로 생성.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamConsumerConfig implements DisposableBean {

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final UserEventStreamBridge bridge;

    private final String consumerName = "trading-core-" + UUID.randomUUID();
    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;

    // Redis 미기동/일시 장애로 그룹 생성·구독이 실패해도 trading-core 부팅 자체는 막지 않는다 —
    // @PostConstruct에서 예외가 rethrow되면 ApplicationContext 로딩이 실패해 매매 프로세스 전체가
    // 못 뜬다. 컨슈머가 결국 못 떠도 Task5의 드리프트 복구 스케쥴러(5분 주기)가 있어 완전한 무한
    // 침묵은 아니다 — 로그는 남긴다.
    @PostConstruct
    void start() {
        try {
            ensureGroup(RedisStreamConfig.USER_DELETED_STREAM);
            ensureGroup(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM);

            var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                    .builder()
                    .pollTimeout(Duration.ofSeconds(2))
                    .build();
            container = StreamMessageListenerContainer.create(connectionFactory, options);

            container.receive(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, consumerName),
                    StreamOffset.create(RedisStreamConfig.USER_DELETED_STREAM, ReadOffset.lastConsumed()),
                    bridge::handleUserDeletedRecord);
            container.receive(Consumer.from(RedisStreamConfig.TRADING_CONSUMER_GROUP, consumerName),
                    StreamOffset.create(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, ReadOffset.lastConsumed()),
                    bridge::handleProfileChangedRecord);
            container.start();
            log.info("Redis Stream 컨슈머 시작 — consumer={}", consumerName);
        } catch (Exception e) {
            log.error("Redis Stream 컨슈머 시작 실패 — 드리프트 복구 스케쥴러가 대신 동기화할 때까지 매매 프로세스는 그대로 기동", e);
        }
    }

    // 스트림이 아직 없으면 MKSTREAM으로 함께 생성, 그룹이 이미 있으면(BUSYGROUP) 무시
    private void ensureGroup(String streamKey) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), RedisStreamConfig.TRADING_CONSUMER_GROUP);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    @Override
    public void destroy() {
        if (container != null) {
            container.stop();
        }
    }
}
