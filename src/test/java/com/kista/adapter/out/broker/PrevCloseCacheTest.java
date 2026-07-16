package com.kista.adapter.out.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrevCloseCache — 전일종가 캐시 테스트")
class PrevCloseCacheTest {

    private final PrevCloseCache cache = new PrevCloseCache();

    @Test
    @DisplayName("같은 (symbol, date, bucket) 재조회 시 fetcher는 1회만 호출된다")
    void same_key_calls_fetcher_once() {
        AtomicInteger callCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 16);

        Optional<BigDecimal> first = cache.getOrFetch("SOXL", date, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });
        // 캐시 히트 시 이 fetcher는 호출되지 않아야 하고, 반환값도 첫 조회 값이어야 함
        Optional<BigDecimal> second = cache.getOrFetch("SOXL", date, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.ONE);
        });

        assertThat(first).contains(BigDecimal.TEN);
        assertThat(second).contains(BigDecimal.TEN);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 date는 캐시가 분리되어 fetcher가 각각 호출된다")
    void different_date_calls_fetcher_separately() {
        AtomicInteger callCount = new AtomicInteger();
        LocalDate date1 = LocalDate.of(2026, 7, 16);
        LocalDate date2 = LocalDate.of(2026, 7, 17);

        cache.getOrFetch("SOXL", date1, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });
        cache.getOrFetch("SOXL", date2, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 bucket은 캐시가 분리되어 fetcher가 각각 호출된다")
    void different_bucket_calls_fetcher_separately() {
        AtomicInteger callCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 16);

        cache.getOrFetch("SOXL", date, "OPEN", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });
        cache.getOrFetch("SOXL", date, "CLOSED", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("fetcher가 Optional.empty()를 반환해도 캐시되어 같은 키 재조회 시 fetcher가 재호출되지 않는다 (허용된 트레이드오프)")
    void empty_result_is_also_cached() {
        AtomicInteger callCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 16);

        Optional<BigDecimal> first = cache.getOrFetch("SOXL", date, "", () -> {
            callCount.incrementAndGet();
            return Optional.empty();
        });
        Optional<BigDecimal> second = cache.getOrFetch("SOXL", date, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });

        assertThat(first).isEmpty();
        assertThat(second).isEmpty(); // 캐시된 empty가 그대로 반환 — 회귀 방지
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("2-인자 오버로드는 bucket \"\" 기본값을 사용해 3-인자 \"\"와 같은 키를 공유한다")
    void two_arg_overload_uses_empty_bucket_default() {
        AtomicInteger callCount = new AtomicInteger();
        LocalDate date = LocalDate.of(2026, 7, 16);

        Optional<BigDecimal> first = cache.getOrFetch("SOXL", date, () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.TEN);
        });
        Optional<BigDecimal> second = cache.getOrFetch("SOXL", date, "", () -> {
            callCount.incrementAndGet();
            return Optional.of(BigDecimal.ONE);
        });

        assertThat(first).contains(BigDecimal.TEN);
        assertThat(second).contains(BigDecimal.TEN);
        assertThat(callCount.get()).isEqualTo(1);
    }
}
