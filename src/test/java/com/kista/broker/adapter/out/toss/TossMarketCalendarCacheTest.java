package com.kista.broker.adapter.out.toss;

import com.kista.broker.domain.model.toss.TossMarketSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("TossMarketCalendarCache 단위 테스트")
class TossMarketCalendarCacheTest {

    private static final ZoneId US_EASTERN = ZoneId.of("America/New_York");

    // 가짜 시계 — 테스트마다 원하는 시각으로 이동시킬 수 있도록 참조를 노출
    private static final class MutableClock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        Instant get() { return now; }
    }

    @Test
    @DisplayName("과거 확정 날짜는 영구 캐시 — 24시간 뒤에도 재조회하지 않는다")
    void pastDate_cachedForever() {
        Instant base = LocalDate.of(2026, 7, 30).atStartOfDay(US_EASTERN).toInstant(); // 오늘=7/30 기준
        MutableClock clock = new MutableClock(base);
        TossMarketCalendarCache cache = new TossMarketCalendarCache(Duration.ofMinutes(15), clock::get);
        LocalDate pastDate = LocalDate.of(2026, 7, 29); // 오늘 이전 확정 거래일
        AtomicInteger fetchCount = new AtomicInteger();
        TossMarketSession session = new TossMarketSession(pastDate, null, null, null);

        Optional<TossMarketSession> first = cache.getOrFetch(pastDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });

        clock.now = base.plus(Duration.ofHours(24)); // 24시간 경과
        Optional<TossMarketSession> second = cache.getOrFetch(pastDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });

        assertThat(first).contains(session);
        assertThat(second).contains(session);
        assertThat(fetchCount.get()).isEqualTo(1); // 재조회 없음
    }

    @Test
    @DisplayName("오늘·미래 날짜는 15분 TTL — 만료 후 재조회한다")
    void todayOrFuture_expiresAfterTtl() {
        Instant base = LocalDate.of(2026, 7, 30).atStartOfDay(US_EASTERN).toInstant();
        MutableClock clock = new MutableClock(base);
        TossMarketCalendarCache cache = new TossMarketCalendarCache(Duration.ofMinutes(15), clock::get);
        LocalDate today = LocalDate.of(2026, 7, 30);
        AtomicInteger fetchCount = new AtomicInteger();
        TossMarketSession session = new TossMarketSession(today, null, null, null);

        cache.getOrFetch(today, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });

        clock.now = base.plus(Duration.ofMinutes(14)); // TTL 이내 — 캐시 히트
        cache.getOrFetch(today, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });
        assertThat(fetchCount.get()).isEqualTo(1);

        clock.now = base.plus(Duration.ofMinutes(16)); // TTL 초과 — 재조회
        cache.getOrFetch(today, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });
        assertThat(fetchCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("빈 응답(empty)은 캐싱하지 않는다 — 다음 조회 시 다시 fetcher 호출")
    void emptyResponse_notCached() {
        Instant base = LocalDate.of(2026, 7, 30).atStartOfDay(US_EASTERN).toInstant();
        MutableClock clock = new MutableClock(base);
        TossMarketCalendarCache cache = new TossMarketCalendarCache(Duration.ofMinutes(15), clock::get);
        LocalDate pastDate = LocalDate.of(2026, 7, 29);
        AtomicInteger fetchCount = new AtomicInteger();

        Optional<TossMarketSession> first = cache.getOrFetch(pastDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.empty();
        });
        Optional<TossMarketSession> second = cache.getOrFetch(pastDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.empty();
        });

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(fetchCount.get()).isEqualTo(2); // 매번 재조회
    }

    @Test
    @DisplayName("조회 시 90일 초과 과거 키를 정리한다")
    void oldKeys_evictedOnAccess() {
        Instant base = LocalDate.of(2026, 7, 30).atStartOfDay(US_EASTERN).toInstant();
        MutableClock clock = new MutableClock(base);
        TossMarketCalendarCache cache = new TossMarketCalendarCache(Duration.ofMinutes(15), clock::get);
        LocalDate veryOldDate = LocalDate.of(2026, 1, 1); // 90일 훨씬 이전
        TossMarketSession session = new TossMarketSession(veryOldDate, null, null, null);
        AtomicInteger fetchCount = new AtomicInteger();

        cache.getOrFetch(veryOldDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });
        // 다른 날짜 조회를 트리거해 정리 로직이 실행되도록 함
        cache.getOrFetch(LocalDate.of(2026, 7, 29), () -> Optional.of(
                new TossMarketSession(LocalDate.of(2026, 7, 29), null, null, null)));

        cache.getOrFetch(veryOldDate, () -> {
            fetchCount.incrementAndGet();
            return Optional.of(session);
        });

        assertThat(fetchCount.get()).isEqualTo(2); // 정리되어 재조회됨
    }
}
