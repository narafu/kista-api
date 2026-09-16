package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Redis Stream 메시지를 trading-core 프로세스 안에서 로컬 이벤트로 재발행하는 브릿지 —
// 기존 4개 cascade/동기화 리스너(UserCascadeListener/AccountUserCascadeListener/
// StrategyUserCascadeListener/UserNotifyProfileSyncListener)를 한 줄도 고치지 않고 그대로
// 재사용하기 위해, 원격 전달 문제를 여기서만 해소한다. 실제 트랜잭션 배선(@Transactional)은
// 별도 빈 UserEventRepublisher가 담당 — 같은 클래스 안에서 this.republish...()로 호출하면
// Spring 프록시를 우회해(self-invocation) @Transactional이 무력화되므로, 반드시 다른 빈을
// 거쳐 호출해야 AFTER_COMMIT phase 리스너(fallbackExecution 없는 UserCascadeListener 등)가
// 실제로 발화한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamBridge {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UserEventRepublisher republisher;

    public void handleUserDeletedRecord(MapRecord<String, String, String> record) {
        UserDeletedEvent event = objectMapper.readValue(payload(record), UserDeletedEvent.class);
        republisher.republishUserDeleted(event);
        ack(RedisStreamConfig.USER_DELETED_STREAM, record.getId());
    }

    public void handleProfileChangedRecord(MapRecord<String, String, String> record) {
        UserNotifyProfileChangedEvent event = objectMapper.readValue(payload(record), UserNotifyProfileChangedEvent.class);
        republisher.republishProfileChanged(event);
        ack(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, record.getId());
    }

    private String payload(MapRecord<String, String, String> record) {
        return record.getValue().get("payload");
    }

    private void ack(String streamKey, RecordId recordId) {
        redisTemplate.opsForStream().acknowledge(streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, recordId);
    }
}
