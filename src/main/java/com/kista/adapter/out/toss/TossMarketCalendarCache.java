package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossMarketSession;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

// market-calendar 날짜별 캐시 — 과거(미국 동부 기준 오늘 이전) 날짜는 확정이라 영구, 오늘·미래는 15분 TTL
// 실패(empty)·예외는 캐싱하지 않음 — 과거 날짜에 일시 장애가 영구 오염되는 것 방지 (PrevCloseCache 스타일, Spring bean 아님)
final class TossMarketCalendarCache {

    private static final ZoneId US_EASTERN = ZoneId.of("America/New_York");
    private static final int RETENTION_DAYS = 90; // 조회 시 정리 대상 — 90일 초과 과거 키 삭제

    // expiresAt == null → 영구 보관 (과거 확정 날짜)
    private record Entry(TossMarketSession value, Instant expiresAt) {}

    private final Duration ttl;
    private final Supplier<Instant> clock;
    private final ConcurrentMap<LocalDate, Entry> cache = new ConcurrentHashMap<>();

    TossMarketCalendarCache(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    Optional<TossMarketSession> getOrFetch(LocalDate date, Supplier<Optional<TossMarketSession>> fetcher) {
        Instant now = clock.get();
        Entry cached = cache.get(date);
        if (cached != null && (cached.expiresAt() == null || now.isBefore(cached.expiresAt()))) {
            return Optional.of(cached.value());
        }
        Optional<TossMarketSession> fresh = fetcher.get();
        LocalDate usToday = now.atZone(US_EASTERN).toLocalDate();
        // 과거 확정 날짜는 영구(expiresAt=null), 오늘·미래는 TTL 부여 — empty는 저장하지 않음
        fresh.ifPresent(v -> cache.put(date,
                new Entry(v, date.isBefore(usToday) ? null : clock.get().plus(ttl))));
        cache.keySet().removeIf(d -> d.isBefore(usToday.minusDays(RETENTION_DAYS)));
        return fresh;
    }
}
