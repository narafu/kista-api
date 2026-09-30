package com.kista.platform.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

// 프로세스 간 Redis Stream 전달의 공통 기법 — "payload" 단일 필드 봉투(XADD/읽기), 컨슈머 그룹 보장(BUSYGROUP 무시),
// pending 복구(XCLAIM). root(user.* 발행, app.error 구독)와 trading-core(user.* 구독, app.error 발행) 양쪽이
// 같은 규약을 쓰도록 여기 한 곳에 둔다. 빈이 아니라 순수 정적 유틸 — 스트림 키·그룹명은 RedisStreamConfig.
@Slf4j
public final class RedisStreams {

    public static final String PAYLOAD_FIELD = "payload"; // 봉투 필드명 — 발행·구독 양쪽 공용
    private static final long DEFAULT_MAXLEN = 1000; // 무기한 적재 방지 — 최근 N건만 근사 보존

    private RedisStreams() {
    }

    // JSON payload 1필드로 감싸 XADD — 기본 MAXLEN(최근 1000건 근사 보존)
    public static void add(StringRedisTemplate redisTemplate, String streamKey, String payloadJson) {
        add(redisTemplate, streamKey, payloadJson, DEFAULT_MAXLEN);
    }

    // JSON payload 1필드로 감싸 XADD — maxLen 근사 트리밍(초과 시 오래된 것부터 삭제)으로 무한 적재를 막는다.
    // 소비자가 오래 멈춰도 보존해야 하는 스트림은 상한을 키워서 호출한다
    public static void add(StringRedisTemplate redisTemplate, String streamKey, String payloadJson, long maxLen) {
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                        .in(streamKey)
                        .ofMap(Collections.singletonMap(PAYLOAD_FIELD, payloadJson)),
                XAddOptions.maxlen(maxLen).approximateTrimming(true));
    }

    // 봉투에서 payload JSON 추출
    public static String payload(MapRecord<String, String, String> record) {
        return record.getValue().get(PAYLOAD_FIELD);
    }

    // 스트림이 아직 없으면 MKSTREAM으로 함께 생성, 그룹이 이미 있으면(BUSYGROUP) 무시
    public static void ensureGroup(StringRedisTemplate redisTemplate, String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
        } catch (RedisSystemException e) {
            if (!String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    // 그룹 전체의 idle 항목을 idleThreshold 기준으로 조회해 recoveryConsumer로 claim 후 handler로 재처리 — 처리 건수 반환.
    // 재시도 한도 없이(Integer.MAX_VALUE) 복구한다
    public static int reclaimPending(StringRedisTemplate redisTemplate, String streamKey, String group,
                                     String recoveryConsumer, Duration idleThreshold, int batchSize,
                                     Consumer<MapRecord<String, String, String>> handler) {
        return reclaimPending(redisTemplate, streamKey, group, recoveryConsumer, idleThreshold, batchSize, Integer.MAX_VALUE, handler);
    }

    // 그룹 전체의 idle 항목을 idleThreshold 기준으로 조회해 recoveryConsumer로 claim 후 handler로 재처리 — 처리 건수 반환.
    // 컨슈머가 읽고 ack 전에 죽어 pending으로 남은 메시지의 복구 경로. 스트림/그룹이 아직 없으면(NOGROUP) 0.
    // 전달 횟수가 maxDeliveries를 넘은 메시지는 claim·재처리하지 않고 ack로 포기한다(poison의 무한 재시도 방지, 반환 건수 제외)
    public static int reclaimPending(StringRedisTemplate redisTemplate, String streamKey, String group,
                                     String recoveryConsumer, Duration idleThreshold, int batchSize, int maxDeliveries,
                                     Consumer<MapRecord<String, String, String>> handler) {
        StreamOperations<String, String, String> ops = redisTemplate.opsForStream();
        PendingMessages pending;
        try {
            pending = ops.pending(streamKey, group, Range.unbounded(), batchSize, idleThreshold);
        } catch (RedisSystemException e) {
            if (String.valueOf(e.getCause()).contains("NOGROUP")) {
                return 0;
            }
            throw e;
        }
        if (pending.isEmpty()) {
            return 0;
        }
        // 한도 초과 메시지는 포기(ack), 나머지만 claim 대상으로 모은다
        List<RecordId> retryIds = new ArrayList<>();
        for (PendingMessage message : pending) {
            if (message.getTotalDeliveryCount() > maxDeliveries) {
                log.error("{} 스트림 메시지 재시도 한도({}) 초과 — 포기(ack) recordId={}", streamKey, maxDeliveries, message.getId());
                // 포기 ack 실패가 같은 배치의 재시도 대상 claim을 막지 않도록 격리 — 실패하면 다음 주기에 다시 포기 대상이 된다
                try {
                    ops.acknowledge(streamKey, group, message.getId());
                } catch (Exception ackEx) {
                    log.warn("{} 스트림 포기 ack 실패 — recordId={}: {}", streamKey, message.getId(), ackEx.getMessage());
                }
            } else {
                retryIds.add(message.getId());
            }
        }
        if (retryIds.isEmpty()) {
            return 0;
        }
        List<MapRecord<String, String, String>> claimed = ops.claim(streamKey, group, recoveryConsumer, idleThreshold, retryIds.toArray(RecordId[]::new));
        for (MapRecord<String, String, String> record : claimed) {
            handler.accept(record);
        }
        return claimed.size();
    }
}
