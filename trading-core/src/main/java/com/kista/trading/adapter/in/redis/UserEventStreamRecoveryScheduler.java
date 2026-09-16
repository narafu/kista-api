package com.kista.trading.adapter.in.redis;

import com.kista.platform.redis.RedisStreamConfig;
import com.kista.platform.scheduling.SchedulerJobRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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

    // 그룹 전체의 idle 항목을 idleThreshold 기준으로 조회해 recovery consumer로 claim 후 재처리
    private void reclaimStream(String streamKey, Duration idleThreshold, Consumer<MapRecord<String, String, String>> handler) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        PendingMessages pending;
        try {
            pending = ops.pending(streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, Range.unbounded(), CLAIM_BATCH_SIZE, idleThreshold);
        } catch (RedisSystemException e) {
            // 스트림/그룹이 아직 없으면(NOGROUP) 복구 대상 자체가 없다는 뜻 — 정상 컨슈머(UserEventStreamConsumerConfig)가
            // 기동 시 그룹을 생성하므로 정상 운영 중엔 발생하지 않지만, 방어적으로 스킵한다
            if (String.valueOf(e.getCause()).contains("NOGROUP")) {
                return;
            }
            throw e;
        }
        if (pending.isEmpty()) {
            return;
        }
        RecordId[] recordIds = pending.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
        List<MapRecord<String, String, String>> claimed = ops.claim(
                streamKey, RedisStreamConfig.TRADING_CONSUMER_GROUP, RECOVERY_CONSUMER, idleThreshold, recordIds);
        for (MapRecord<String, String, String> record : claimed) {
            log.warn("Redis Stream pending 복구 처리 — stream={}, recordId={}", streamKey, record.getId());
            handler.accept(record);
        }
    }
}
