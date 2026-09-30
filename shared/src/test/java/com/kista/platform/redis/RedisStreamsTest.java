package com.kista.platform.redis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamsTest {

    private static final String STREAM = "stream:test";
    private static final String GROUP = "test-group";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StreamOperations<String, Object, Object> stubOps(StringRedisTemplate redisTemplate) {
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) ops);
        return ops;
    }

    @Test
    void add_3인자는_기본_MAXLEN_1000으로_위임한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var ops = stubOps(redisTemplate);

        RedisStreams.add(redisTemplate, STREAM, "{}");

        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(ops).add(any(MapRecord.class), options.capture());
        assertThat(options.getValue().getMaxlen()).isEqualTo(1000L);
    }

    @Test
    void add_maxLen_오버로드는_지정한_상한으로_근사_트리밍한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var ops = stubOps(redisTemplate);

        RedisStreams.add(redisTemplate, STREAM, "{}", 10_000);

        ArgumentCaptor<XAddOptions> options = ArgumentCaptor.forClass(XAddOptions.class);
        verify(ops).add(any(MapRecord.class), options.capture());
        assertThat(options.getValue().getMaxlen()).isEqualTo(10_000L);
        assertThat(options.getValue().isApproximateTrimming()).isTrue();
    }

    // 전달 횟수가 maxDeliveries를 넘은 메시지는 claim·재처리 없이 ack로 포기하고, 한도 이내 메시지만 claim한다
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void reclaimPending_재시도_한도_초과_메시지는_claim하지_않고_ack한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations ops = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(ops);
        Consumer consumer = Consumer.from(GROUP, "c1");
        RecordId poison = RecordId.of("1-0");
        RecordId retry = RecordId.of("2-0");
        PendingMessages pending = new PendingMessages(GROUP, List.of(
                new PendingMessage(poison, consumer, Duration.ofMinutes(5), 25),
                new PendingMessage(retry, consumer, Duration.ofMinutes(5), 24)));
        when(ops.pending(eq(STREAM), eq(GROUP), any(), anyLong(), any(Duration.class))).thenReturn(pending);
        MapRecord<String, String, String> retryRecord = StreamRecords.newRecord().in(STREAM).withId(retry).ofMap(Map.of("payload", "x"));
        when(ops.claim(eq(STREAM), eq(GROUP), eq("rc"), any(Duration.class), any(RecordId[].class))).thenReturn(List.of(retryRecord));
        List<MapRecord<String, String, String>> handled = new ArrayList<>();

        int count = RedisStreams.reclaimPending(redisTemplate, STREAM, GROUP, "rc", Duration.ofSeconds(60), 100, 24, handled::add);

        assertThat(count).isEqualTo(1);
        assertThat(handled).containsExactly(retryRecord);
        verify(ops).acknowledge(STREAM, GROUP, poison);
        ArgumentCaptor<RecordId[]> claimIds = ArgumentCaptor.forClass(RecordId[].class);
        verify(ops).claim(eq(STREAM), eq(GROUP), eq("rc"), any(Duration.class), claimIds.capture());
        assertThat(claimIds.getValue()).containsExactly(retry);
    }

    // 전부 한도 초과면 claim 자체를 호출하지 않고 0을 반환한다
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void reclaimPending_전부_한도_초과면_claim을_호출하지_않는다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        StreamOperations ops = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(ops);
        RecordId poison = RecordId.of("1-0");
        PendingMessages pending = new PendingMessages(GROUP, List.of(
                new PendingMessage(poison, Consumer.from(GROUP, "c1"), Duration.ofMinutes(5), 99)));
        when(ops.pending(eq(STREAM), eq(GROUP), any(), anyLong(), any(Duration.class))).thenReturn(pending);

        int count = RedisStreams.reclaimPending(redisTemplate, STREAM, GROUP, "rc", Duration.ofSeconds(60), 100, 24, r -> { });

        assertThat(count).isZero();
        verify(ops).acknowledge(STREAM, GROUP, poison);
        verify(ops, never()).claim(anyString(), anyString(), anyString(), any(Duration.class), any(RecordId[].class));
    }
}
