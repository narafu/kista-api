package com.kista.broker.adapter.out.toss;

import com.kista.broker.domain.model.toss.TossStockInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

// 종목 기본정보(symbol 키) TTL 캐시 — 저변동 데이터라 6시간 TTL, 성공 응답만 저장 (실패 empty·예외는 미캐싱)
// 키는 Ticker enum 종목 수(소수)로 한정되어 크기 관리 불필요 (PrevCloseCache 스타일, Spring bean 아님)
final class TossStockInfoCache {

    private record Entry(TossStockInfo value, Instant expiresAt) {}

    private final Duration ttl;
    private final Supplier<Instant> clock;
    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    TossStockInfoCache(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    Optional<TossStockInfo> getOrFetch(String symbol, Supplier<Optional<TossStockInfo>> fetcher) {
        Entry cached = cache.get(symbol);
        if (cached != null && clock.get().isBefore(cached.expiresAt())) {
            return Optional.of(cached.value());
        }
        Optional<TossStockInfo> fresh = fetcher.get();
        fresh.ifPresent(v -> cache.put(symbol, new Entry(v, clock.get().plus(ttl))));
        return fresh;
    }
}
