package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

// Redis Stream 메시지를 trading-core 프로세스 안에서 로컬 이벤트로 재발행하는 브릿지 —
// 기존 4개 cascade/동기화 리스너(UserCascadeListener/AccountUserCascadeListener/
// StrategyUserCascadeListener/UserNotifyProfileSyncListener)를 한 줄도 고치지 않고 그대로
// 재사용하기 위해, 원격 전달 문제를 여기서만 해소한다. @Transactional로 감싸야
// AFTER_COMMIT phase 리스너가 실제로 발화한다(트랜잭션이 없으면 fallbackExecution 없는
// 리스너는 조용히 스킵됨).
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamBridge {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public void handleUserDeletedRecord(MapRecord<String, String, String> record) {
        UserDeletedEvent event = objectMapper.readValue(payload(record), UserDeletedEvent.class);
        republishUserDeleted(event);
        ack(RedisStreamConfig.USER_DELETED_STREAM, record.getId());
    }

    public void handleProfileChangedRecord(MapRecord<String, String, String> record) {
        UserNotifyProfileChangedEvent event = objectMapper.readValue(payload(record), UserNotifyProfileChangedEvent.class);
        republishProfileChanged(event);
        ack(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, record.getId());
    }

    @Transactional
    void republishUserDeleted(UserDeletedEvent event) {
        eventPublisher.publishEvent(event);
    }

    @Transactional
    void republishProfileChanged(UserNotifyProfileChangedEvent event) {
        eventPublisher.publishEvent(event);
    }

    private String payload(MapRecord<String, String, String> record) {
        return record.getValue().get("payload");
    }

    private void ack(String streamKey, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, recordId);
    }
}
