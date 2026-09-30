package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreamSubscriber;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;

import java.util.concurrent.TimeUnit;

// root가 stream:user.deleted로 발행한 UserDeletedEvent를 trading-core 로컬 이벤트로 재발행하는 구독자(cascade soft-delete 트리거).
// 구독·ack·cancelOnError=false·끊긴 구독 재기동·XCLAIM 복구는 platform RedisStreamSubscriber가 맡고, 이 클래스는
// 브릿지 위임(onRecord)·실패 정책·기동·5분 주기 복구 배선만 가진다. 정합성 이벤트라 역직렬화 실패(poison)만 ack로 버리고,
// 재발행·DB 일시 오류는 ack하지 않고 pending으로 남겨 XCLAIM 재시도하며 maxDeliveries(24회)에서 포기한다. 컨슈머 접두사가 UserNotifyProfileStreamConsumer와 같아 복구 컨슈머 이름
// (trading-core-recovery)도 같지만 스트림이 달라 pending 추적이 충돌하지 않는다. 옛 UserEventStreamRecoveryScheduler가
// 쓰던 SchedulerJobRunner(STARTED/COMPLETED 이벤트)는 trading-core에 SchedulerLifecycleEvent 리스너가 없어
// 로그 이상의 효과가 없었으므로 베이스의 recoverPending()을 직접 호출한다.
@Component
public class UserDeletedStreamConsumer extends RedisStreamSubscriber {

    private final UserEventStreamBridge bridge; // 역직렬화 + 로컬 이벤트 재발행 위임

    public UserDeletedStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                     UserEventStreamBridge bridge) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.USER_DELETED_STREAM,
                RedisStreamConfig.TRADING_CONSUMER_GROUP, "trading-core");
        this.bridge = bridge;
    }

    // 기동 시 구독 시작 — Redis 장애로 실패해도 삼키고 매매 프로세스는 그대로 기동(다음 복구 주기에 재시도)
    @PostConstruct
    void init() {
        start();
    }

    // 봉투 payload를 로컬 이벤트로 재발행 — 예외는 베이스가 warn 후 ackOnFailure 정책으로 ack 여부 결정
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        bridge.handleUserDeletedRecord(record);
    }

    @Override
    protected boolean ackOnFailure(MapRecord<String, String, String> record, Exception e) {
        // 재시도해도 결과가 같은 결정적 실패(역직렬화 오류·payload 필드 누락 IAE/NPE)는 poison으로 ack, 그 외(DB·트랜잭션 일시 오류)만 재시도
        return e instanceof JacksonException || e instanceof IllegalArgumentException || e instanceof NullPointerException;
    }

    // 복구 주기 5분 x 24회 = 약 2시간 재시도 후 포기(ack)
    @Override
    protected int maxDeliveries() {
        return 24;
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
