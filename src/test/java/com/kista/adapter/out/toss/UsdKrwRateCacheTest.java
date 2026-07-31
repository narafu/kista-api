package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossExchangeRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UsdKrwRateCache 단위 테스트")
@Execution(ExecutionMode.SAME_THREAD)
class UsdKrwRateCacheTest {

    @Test
    @DisplayName("TTL 내 재조회: fetcher는 1회만 호출된다")
    void withinTtl_fetcherCalledOnce() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        UsdKrwRateCache cache = new UsdKrwRateCache(Duration.ofSeconds(60), clock::get);
        AtomicInteger callCount = new AtomicInteger();
        TossExchangeRate rate = new TossExchangeRate(new BigDecimal("1400.00"), new BigDecimal("1400.00"));

        TossExchangeRate first = cache.getOrFetch(() -> { callCount.incrementAndGet(); return rate; });
        clock.set(clock.get().plusSeconds(59));
        TossExchangeRate second = cache.getOrFetch(() -> { callCount.incrementAndGet(); return rate; });

        assertThat(first).isEqualTo(rate);
        assertThat(second).isEqualTo(rate);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("60초 경과 후 재조회: fetcher가 다시 호출된다")
    void afterTtlExpires_fetcherCalledAgain() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        UsdKrwRateCache cache = new UsdKrwRateCache(Duration.ofSeconds(60), clock::get);
        AtomicInteger callCount = new AtomicInteger();
        TossExchangeRate rate = new TossExchangeRate(new BigDecimal("1400.00"), new BigDecimal("1400.00"));

        cache.getOrFetch(() -> { callCount.incrementAndGet(); return rate; });
        clock.set(clock.get().plusSeconds(60));
        cache.getOrFetch(() -> { callCount.incrementAndGet(); return rate; });

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("ZERO 폴백 값은 캐싱하지 않고 매번 재조회한다")
    void zeroFallback_notCached() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        UsdKrwRateCache cache = new UsdKrwRateCache(Duration.ofSeconds(60), clock::get);
        AtomicInteger callCount = new AtomicInteger();
        TossExchangeRate zero = new TossExchangeRate(BigDecimal.ZERO, BigDecimal.ZERO);

        cache.getOrFetch(() -> { callCount.incrementAndGet(); return zero; });
        cache.getOrFetch(() -> { callCount.incrementAndGet(); return zero; });

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fetcher 예외는 캐싱하지 않고 그대로 전파한다")
    void exception_notCachedAndPropagated() {
        AtomicReference<Instant> clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        UsdKrwRateCache cache = new UsdKrwRateCache(Duration.ofSeconds(60), clock::get);
        AtomicInteger callCount = new AtomicInteger();

        assertThatThrownBy(() -> cache.getOrFetch(() -> {
            callCount.incrementAndGet();
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class).hasMessage("boom");

        assertThatThrownBy(() -> cache.getOrFetch(() -> {
            callCount.incrementAndGet();
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);

        assertThat(callCount.get()).isEqualTo(2);
    }
}
