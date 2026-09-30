package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.redis.RedisStreams;
import com.kista.platform.scheduling.SchedulerJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// XAUTOCLAIM 대응 — UserEventStreamConsumerConfig의 컨슈머가 메시지를 읽고 ack 전 크래시하면
// pending 상태로 남는다. 5분마다 유휴 60초 이상 pending 항목을 이 스케쥴러 전용 컨슈머로
// claim해 UserEventStreamBridge의 기존 handle 메서드로 재처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventStreamRecoveryScheduler {

    private static final Duration IDLE_THRESHOLD = Duration.ofSeconds(60);
    private static final String RECOVERY_CONSUMER = "trading-core-recovery";
    private static final int CLAIM_BATCH_SIZE = 100;

    private final StringRedisTemplate redisTemplate;
    private final UserEventStreamBridge bridge;
    private final SchedulerJobRunner schedulerJobRunner;

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void run() {
        schedulerJobRunner.run("Redis Stream pending 복구", () -> reclaimPending(IDLE_THRESHOLD));
    }

    void reclaimPending(Duration idleThreshold) {
        reclaimStream(RedisStreamConfig.USER_DELETED_STREAM, idleThreshold, bridge::handleUserDeletedRecord);
        reclaimStream(RedisStreamConfig.USER_NOTIFY_PROFILE_CHANGED_STREAM, idleThreshold, bridge::handleProfileChangedRecord);
    }

    // claim·재처리 규약은 platform RedisStreams(root app.error 복구와 공용) — 복구된 건마다 경고 로그만 여기서 남긴다
    private void reclaimStream(String streamKey, Duration idleThreshold, Consumer<MapRecord<String, String, String>> handler) {
        RedisStreams.reclaimPending(redisTemplate, streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP,
                RECOVERY_CONSUMER, idleThreshold, CLAIM_BATCH_SIZE, record -> {
                    log.warn("Redis Stream pending 복구 처리 — stream={}, recordId={}", streamKey, record.getId());
                    handler.accept(record);
                });
    }
}
