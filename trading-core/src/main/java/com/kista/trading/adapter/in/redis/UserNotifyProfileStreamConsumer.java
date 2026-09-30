package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreamSubscriber;
import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// root가 stream:user.notify-profile.changed로 발행한 UserNotifyProfileChangedEvent를 trading-core 로컬 이벤트로 재발행하는 구독자(user_notify_profile 복제본 동기화 트리거).
// 구독·ack·cancelOnError=false·끊긴 구독 재기동·XCLAIM 복구는 platform RedisStreamSubscriber가 맡고, 이 클래스는
// 브릿지 위임(onRecord)·실패 정책·기동·5분 주기 복구 배선만 가진다. 이 이벤트의 소비자(UserNotifyProfileSyncListener)는
// 마지막 쓰기가 이기는 upsert라 실패 건을 나중에 XCLAIM으로 재적용하면 그 사이 반영된 더 새로운 변경을 옛 값으로 덮어쓴다 —
// 그래서 UserDeletedStreamConsumer와 달리 실패해도 ack(베이스 기본 정책)하고 warn만 남긴다. 누락된 변경은 사용자의 다음 설정 변경이 바로잡는다. 컨슈머 접두사가 UserDeletedStreamConsumer와 같아 복구 컨슈머 이름
// (trading-core-recovery)도 같지만 스트림이 달라 pending 추적이 충돌하지 않는다. 옛 UserEventStreamRecoveryScheduler가
// 쓰던 SchedulerJobRunner(STARTED/COMPLETED 이벤트)는 trading-core에 SchedulerLifecycleEvent 리스너가 없어
// 로그 이상의 효과가 없었으므로 베이스의 recoverPending()을 직접 호출한다.
@Component
public class UserNotifyProfileStreamConsumer extends RedisStreamSubscriber {

    private final UserEventStreamBridge bridge; // 역직렬화 + 로컬 이벤트 재발행 위임

    public UserNotifyProfileStreamConsumer(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate,
                                           UserEventStreamBridge bridge) {
        super(connectionFactory, redisTemplate, RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM,
                RedisStreamConfig.TRADING_CONSUMER_GROUP, "trading-core");
        this.bridge = bridge;
    }

    // 기동 시 구독 시작 — Redis 장애로 실패해도 삼키고 매매 프로세스는 그대로 기동(다음 복구 주기에 재시도)
    @PostConstruct
    void init() {
        start();
    }

    // 봉투 payload를 로컬 이벤트로 재발행 — 예외는 베이스가 warn 후 ack(last-write upsert라 stale 재적용 금지, 클래스 주석 참고)
    @Override
    protected void onRecord(MapRecord<String, String, String> record) {
        bridge.handleProfileChangedRecord(record);
    }

    // 5분마다 끊긴 구독 재기동 + pending 복구
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void scheduledRecovery() {
        recoverPending();
    }
}
