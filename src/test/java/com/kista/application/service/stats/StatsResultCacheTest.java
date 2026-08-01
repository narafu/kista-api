package com.kista.application.service.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
class StatsResultCacheTest {

    // 테스트에서 임의 시각으로 이동시킬 수 있는 가변 Clock — TossRedisTokenStore 테스트 패턴 재사용
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final Instant BASE = Instant.parse("2026-08-01T00:00:00Z");

    private MutableClock clock;
    private StatsResultCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(BASE);
        cache = new StatsResultCache(clock);
    }

    @Test
    void getOrCompute_reusesCachedValue_withinTtl() {
        AtomicInteger callCount = new AtomicInteger();

        String first = cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "value";
        });
        clock.advance(Duration.ofMinutes(4)); // TTL 이내
        String second = cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "value";
        });

        assertThat(first).isEqualTo("value");
        assertThat(second).isEqualTo("value");
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void getOrCompute_recomputes_afterTtlExpires() {
        AtomicInteger callCount = new AtomicInteger();

        cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "value";
        });
        clock.advance(Duration.ofMinutes(6)); // TTL 초과
        String result = cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "value2";
        });

        assertThat(result).isEqualTo("value2");
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void getOrCompute_isolatesDifferentKeys() {
        AtomicInteger callCount = new AtomicInteger();

        String a = cache.getOrCompute("keyA", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "valueA";
        });
        String b = cache.getOrCompute("keyB", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "valueB";
        });

        assertThat(a).isEqualTo("valueA");
        assertThat(b).isEqualTo("valueB");
        assertThat(callCount.get()).isEqualTo(2); // 서로 다른 키는 독립적으로 계산됨
    }

    @Test
    void getOrCompute_doesNotCache_nullResult() {
        AtomicInteger callCount = new AtomicInteger();

        String first = cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return null;
        });
        String second = cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            return "value";
        });

        assertThat(first).isNull();
        assertThat(second).isEqualTo("value");
        assertThat(callCount.get()).isEqualTo(2); // null은 캐싱하지 않아 다시 호출됨
    }

    @Test
    void peek_returnsValidCacheOnly_nullWhenExpired() {
        cache.getOrCompute("key", Duration.ofMinutes(5), () -> "value");

        String withinTtl = cache.peek("key");
        clock.advance(Duration.ofMinutes(6)); // TTL 초과
        String afterExpiry = cache.peek("key");

        assertThat(withinTtl).isEqualTo("value");
        assertThat(afterExpiry).isNull();
    }

    @Test
    void getOrCompute_collapsesConcurrentMisses_intoSingleComputation() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        Callable<String> task = () -> cache.getOrCompute("key", Duration.ofMinutes(5), () -> {
            callCount.incrementAndGet();
            try {
                Thread.sleep(100); // 두 태스크가 동시에 miss를 겪도록 지연
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "value";
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<String>> results = executor.invokeAll(List.of(task, task));
            for (java.util.concurrent.Future<String> result : results) {
                assertThat(result.get()).isEqualTo("value");
            }
        }

        assertThat(callCount.get()).isEqualTo(1); // 동시 miss는 키별 락으로 1회만 계산됨
    }
}
