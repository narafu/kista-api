package com.kista.platform.redis;

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
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

// 프로세스 간 Redis Stream 전달의 공통 기법 — "payload" 단일 필드 봉투(XADD/읽기), 컨슈머 그룹 보장(BUSYGROUP 무시),
// pending 복구(XCLAIM). root(user.* 발행, app.error 구독)와 trading-core(user.* 구독, app.error 발행) 양쪽이
// 같은 규약을 쓰도록 여기 한 곳에 둔다. 빈이 아니라 순수 정적 유틸 — 스트림 키·그룹명은 RedisStreamConfig.
public final class RedisStreams {

    public static final String PAYLOAD_FIELD = "payload"; // 봉투 필드명 — 발행·구독 양쪽 공용
    private static final long DEFAULT_MAXLEN = 1000; // 무기한 적재 방지 — 최근 N건만 근사 보존

    private RedisStreams() {
    }

    // JSON payload 1필드로 감싸 XADD — MAXLEN 근사 트리밍으로 최근 1000건만 보존
    public static void add(StringRedisTemplate redisTemplate, String streamKey, String payloadJson) {
        redisTemplate.opsForStream().add(StreamRecords.newRecord()
                        .in(streamKey)
                        .ofMap(Collections.singletonMap(PAYLOAD_FIELD, payloadJson)),
                XAddOptions.maxlen(DEFAULT_MAXLEN).approximateTrimming(true));
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
    // 컨슈머가 읽고 ack 전에 죽어 pending으로 남은 메시지의 복구 경로. 스트림/그룹이 아직 없으면(NOGROUP) 0.
    public static int reclaimPending(StringRedisTemplate redisTemplate, String streamKey, String group,
                                     String recoveryConsumer, Duration idleThreshold, int batchSize,
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
        RecordId[] recordIds = pending.stream().map(PendingMessage::getId).toArray(RecordId[]::new);
        List<MapRecord<String, String, String>> claimed = ops.claim(streamKey, group, recoveryConsumer, idleThreshold, recordIds);
        for (MapRecord<String, String, String> record : claimed) {
            handler.accept(record);
        }
        return claimed.size();
    }
}
