package com.kista.adapter.out.toss;

import com.kista.domain.model.toss.TossExchangeRate;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

// USD/KRW 환율 60초 TTL 캐시 — 계좌 무관 전역 스칼라 1개라 volatile 단일 Entry + double-check로 충분
// 성공 값(rate > 0)만 저장 — 실패 폴백(ZERO)·예외는 캐싱하지 않고 그대로 반환·전파 (PrevCloseCache 스타일, Spring bean 아님)
final class UsdKrwRateCache {

    // 캐시 항목: 값 + 만료 시각
    private record Entry(TossExchangeRate value, Instant expiresAt) {}

    private final Duration ttl;              // 캐시 유효 기간
    private final Supplier<Instant> clock;    // 테스트에서 시간 주입용 (Thread.sleep 없이 TTL 만료 검증)
    private final Object lock = new Object(); // double-check 락
    private volatile Entry entry;             // 현재 캐시 항목

    UsdKrwRateCache(Duration ttl, Supplier<Instant> clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    // 캐시 조회 — 유효하면 재사용, 만료·미존재면 fetcher로 재조회
    TossExchangeRate getOrFetch(Supplier<TossExchangeRate> fetcher) {
        Entry cached = entry;
        if (cached != null && clock.get().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        synchronized (lock) {
            // 락 대기 중 다른 스레드가 이미 갱신했는지 재확인
            Entry doubleChecked = entry;
            if (doubleChecked != null && clock.get().isBefore(doubleChecked.expiresAt())) {
                return doubleChecked.value();
            }
            TossExchangeRate fresh = fetcher.get();
            // 성공 값(rate > 0)만 캐싱 — ZERO 폴백은 저장하지 않음
            if (fresh != null && fresh.rate() != null && fresh.rate().signum() > 0) {
                entry = new Entry(fresh, clock.get().plus(ttl));
            }
            return fresh;
        }
    }
}
