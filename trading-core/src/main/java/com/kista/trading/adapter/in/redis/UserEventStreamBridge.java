package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreams;
import com.kista.sharedkernel.UserDeletedEvent;
import com.kista.sharedkernel.UserNotifyProfileChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Redis Stream 메시지를 trading-core 프로세스 안에서 로컬 이벤트로 재발행하는 브릿지 —
// 기존 4개 cascade/동기화 리스너(UserCascadeListener/AccountUserCascadeListener/
// StrategyUserCascadeListener/UserNotifyProfileSyncListener)를 한 줄도 고치지 않고 그대로
// 재사용하기 위해, 원격 전달 문제를 여기서만 해소한다. 구독·ack·재기동·XCLAIM 복구는 platform
// RedisStreamSubscriber 서브클래스(UserDeletedStreamConsumer/UserNotifyProfileStreamConsumer)가 맡고,
// 이 클래스는 역직렬화 + 재발행 위임만 한다. 역직렬화 실패(poison)만 ack로 버리고,
// 재발행 실패는 pending으로 남겨 XCLAIM 재시도(최대 24회) 후 포기한다.
// 실제 트랜잭션 배선(@Transactional)은 별도 빈 UserEventRepublisher가 담당 — 같은 클래스 안에서
// this.republish...()로 호출하면 Spring 프록시를 우회해(self-invocation) @Transactional이 무력화되므로,
// 반드시 다른 빈을 거쳐 호출해야 AFTER_COMMIT phase 리스너(fallbackExecution 없는 UserCascadeListener 등)가
// 실제로 발화한다.
@Component
@RequiredArgsConstructor
public class UserEventStreamBridge {

    private final ObjectMapper objectMapper;       // 봉투 payload 역직렬화
    private final UserEventRepublisher republisher; // 트랜잭션 프록시를 거치는 로컬 이벤트 재발행 빈

    public void handleUserDeletedRecord(MapRecord<String, String, String> record) {
        UserDeletedEvent event = objectMapper.readValue(RedisStreams.payload(record), UserDeletedEvent.class);
        republisher.republishUserDeleted(event);
    }

    public void handleProfileChangedRecord(MapRecord<String, String, String> record) {
        UserNotifyProfileChangedEvent event = objectMapper.readValue(RedisStreams.payload(record), UserNotifyProfileChangedEvent.class);
        republisher.republishProfileChanged(event);
    }
}
