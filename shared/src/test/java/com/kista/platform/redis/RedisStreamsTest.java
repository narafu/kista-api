package com.kista.platform.redis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamsTest {

    private static final String STREAM = "stream:test";

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
}
