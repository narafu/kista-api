package com.kista.platform.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamSubscriberTest {

    private static final String STREAM = "stream:test";
    private static final String GROUP = "test-group";

    // onRecord 동작을 주입할 수 있는 테스트용 서브클래스
    private static class TestSubscriber extends RedisStreamSubscriber {
        final List<String> received = new ArrayList<>();
        boolean fail;
        boolean ackOnFailure = true; // 처리 실패 시 ack 여부 — false면 pending 유지

        TestSubscriber(StringRedisTemplate redisTemplate) {
            this(mock(RedisConnectionFactory.class), redisTemplate);
        }

        TestSubscriber(RedisConnectionFactory connectionFactory, StringRedisTemplate redisTemplate) {
            super(connectionFactory, redisTemplate, STREAM, GROUP, "test");
        }

        @Override
        protected void onRecord(MapRecord<String, String, String> record) {
            if (fail) throw new IllegalStateException("처리 실패");
            received.add(RedisStreams.payload(record));
        }

        @Override
        protected boolean ackOnFailure(MapRecord<String, String, String> record, Exception e) {
            return ackOnFailure;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private StreamOperations<String, Object, Object> stubOps(StringRedisTemplate redisTemplate) {
        StreamOperations<String, Object, Object> ops = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn((StreamOperations) ops);
        return ops;
    }

    private static MapRecord<String, String, String> record(String payload) {
        return StreamRecords.newRecord().in(STREAM).withId(RecordId.of("1-0")).ofMap(Map.of("payload", payload));
    }

    // 연결 팩토리가 정지되면 폴링 구독을 취소해 종료 시 즉시 실패 무한 반복(로그 폭주)을 막는다
    @Test
    void connectionFactoryStopped_팩토리_정지_여부를_판정한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        LettuceConnectionFactory lettuce = mock(LettuceConnectionFactory.class);
        TestSubscriber subscriber = new TestSubscriber(lettuce, redisTemplate);

        when(lettuce.isRunning()).thenReturn(true);
        assertThat(subscriber.connectionFactoryStopped()).isFalse();

        when(lettuce.isRunning()).thenReturn(false);
        assertThat(subscriber.connectionFactoryStopped()).isTrue();

        // Lifecycle 미구현 팩토리는 정지 판정 불가 — 기존처럼 구독 유지
        assertThat(new TestSubscriber(redisTemplate).connectionFactoryStopped()).isFalse();
    }

    @Test
    void handle_처리_후_ack한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var ops = stubOps(redisTemplate);
        TestSubscriber subscriber = new TestSubscriber(redisTemplate);
        MapRecord<String, String, String> record = record("hello");

        subscriber.handle(record);

        assertThat(subscriber.received).containsExactly("hello");
        verify(ops).acknowledge(STREAM, GROUP, record.getId());
    }

    // 처리 예외는 삼키고 ack해 poison 메시지의 영구 재시도를 막는다
    @Test
    void handle_처리_예외를_삼키고_ack한다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var ops = stubOps(redisTemplate);
        TestSubscriber subscriber = new TestSubscriber(redisTemplate);
        subscriber.fail = true;
        MapRecord<String, String, String> record = record("x");

        assertThatCode(() -> subscriber.handle(record)).doesNotThrowAnyException();

        verify(ops).acknowledge(STREAM, GROUP, record.getId());
    }

    // ackOnFailure=false면 처리 예외 시 ack하지 않고 pending으로 남겨 XCLAIM 재시도에 맡긴다
    @Test
    void handle_ackOnFailure_false면_처리_예외_시_ack하지_않는다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        var ops = stubOps(redisTemplate);
        TestSubscriber subscriber = new TestSubscriber(redisTemplate);
        subscriber.fail = true;
        subscriber.ackOnFailure = false;
        MapRecord<String, String, String> record = record("x");

        assertThatCode(() -> subscriber.handle(record)).doesNotThrowAnyException();

        verify(ops, never()).acknowledge(anyString(), anyString(), any(RecordId[].class));
    }

    // ack 실패도 삼킨다 — pending으로 남아 XCLAIM 복구 대상이 될 뿐 구독을 죽이지 않는다
    @Test
    void handle_ack_예외도_삼킨다() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.opsForStream()).thenThrow(new IllegalStateException("redis down"));
        TestSubscriber subscriber = new TestSubscriber(redisTemplate);

        assertThatCode(() -> subscriber.handle(record("y"))).doesNotThrowAnyException();
        assertThat(subscriber.received).containsExactly("y");
    }
}
