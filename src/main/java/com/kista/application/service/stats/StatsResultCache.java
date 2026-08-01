package com.kista.application.service.stats;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

// 통계 결과 인메모리 TTL 캐시 — Stats는 "DB 근사 집계"라 수 분 stale 허용 (PreviewDepositCache TTL 패턴 재사용)
// 단일 인스턴스 배포 전제 — 다중 인스턴스로 확장 시 인스턴스별 캐시가 최대 TTL만큼 서로 다를 수 있음
@Component
class StatsResultCache {

    private static final int CLEANUP_THRESHOLD = 1000; // 만료 엔트리 기회적 청소 트리거 크기
    private static final int LOCK_STRIPES = 64;         // 동시 miss 직렬화용 고정 스트라이프 락 수 (키에 날짜 포함돼 카디널리티 무한 → 맵 대신 스트라이프로 메모리 유계)

    private final Clock clock; // TTL 만료 판정용 시계 — 테스트에서 고정 Clock 주입 (TossRedisTokenStore 패턴)
    private final ConcurrentMap<Object, Entry> cache = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = createStripes(); // 키 해시로 매핑하는 고정 크기 락 배열 — 무한 증가·제거 레이스 없음

    @Autowired
    StatsResultCache() {
        this(Clock.systemUTC());
    }

    StatsResultCache(Clock clock) {
        this.clock = clock;
    }

    private static ReentrantLock[] createStripes() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    // 키 해시를 스트라이프에 매핑 — 서로 다른 키가 같은 스트라이프를 공유하면 직렬화되나(드묾) 정합성엔 영향 없음
    private ReentrantLock stripeFor(Object key) {
        return stripes[Math.floorMod(key.hashCode(), LOCK_STRIPES)];
    }

    private record Entry(Object value, Instant expiresAt) {
        boolean isValid(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    // 유효 캐시가 있으면 즉시 반환, 없으면 null — 호출부가 hit/miss에 따라 병렬화 여부를 결정할 수 있게 분리
    @SuppressWarnings("unchecked")
    <V> V peek(Object key) {
        Entry cached = cache.get(key);
        return cached != null && cached.isValid(clock.instant()) ? (V) cached.value() : null;
    }

    <V> V getOrCompute(Object key, Duration ttl, Supplier<V> loader) {
        V peeked = peek(key);
        if (peeked != null) {
            return peeked;
        }
        ReentrantLock lock = stripeFor(key);
        lock.lock();
        try {
            V doubleChecked = peek(key);
            if (doubleChecked != null) {
                return doubleChecked;
            }
            V fresh = loader.get();
            if (fresh == null) {
                return null; // null 결과는 캐시하지 않음 — 일시 실패가 TTL 동안 고착되는 것 방지
            }
            cleanupIfNeeded();
            cache.put(key, new Entry(fresh, clock.instant().plus(ttl)));
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    // 임계치 초과 시 만료 엔트리만 제거 — 별도 스케줄러 없이 쓰기 경로에서 기회적 청소
    private void cleanupIfNeeded() {
        if (cache.size() < CLEANUP_THRESHOLD) {
            return;
        }
        Instant now = clock.instant();
        cache.entrySet().removeIf(entry -> !entry.getValue().isValid(now));
    }
}
