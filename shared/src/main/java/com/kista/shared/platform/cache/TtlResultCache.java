package com.kista.shared.platform.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

// 통계 결과 인메모리 TTL 캐시 — 소비처가 각자 인스턴스를 소유한다(root BenchmarkService 10분, trading-core TradingStatsService 5분).
// 빈이 아닌 평범한 객체라 프로세스 배선 없이 new로 쓴다.
// 단일 인스턴스 배포 전제 — 다중 인스턴스로 확장 시 인스턴스별 캐시가 최대 TTL만큼 서로 다를 수 있음
public class TtlResultCache {

    private static final int CLEANUP_THRESHOLD = 1000; // 만료 엔트리 기회적 청소 트리거 크기
    private static final int LOCK_STRIPES = 64;         // 동시 miss 직렬화용 고정 스트라이프 락 수 (키에 날짜 포함돼 카디널리티 무한 → 맵 대신 스트라이프로 메모리 유계)

    private final Clock clock; // TTL 만료 판정용 시계 — 테스트에서 고정 Clock 주입 (TossRedisTokenStore 패턴)
    private final ConcurrentMap<Object, Entry> cache = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = createStripes(); // 키 해시로 매핑하는 고정 크기 락 배열 — 무한 증가·제거 레이스 없음
    private final AtomicLong generation = new AtomicLong(); // 무효화 세대 — 계산 도중 무효화됐으면 그 결과는 캐시하지 않는다

    public TtlResultCache(Clock clock) {
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
    public <V> V peek(Object key) {
        Entry cached = cache.get(key);
        return cached != null && cached.isValid(clock.instant()) ? (V) cached.value() : null;
    }

    public <V> V getOrCompute(Object key, Duration ttl, Supplier<V> loader) {
        return getOrCompute(key, v -> ttl, loader);
    }

    // TTL을 적재된 값(non-null)에서 계산 — 값이 만료 시각을 알려주는 소스(예: 다음 갱신까지 남은 시간)용
    public <V> V getOrCompute(Object key, Function<? super V, Duration> ttlOf, Supplier<V> loader) {
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
            long gen = generation.get();
            V fresh = loader.get();
            if (fresh == null) {
                return null; // null 결과는 캐시하지 않음 — 일시 실패가 TTL 동안 고착되는 것 방지
            }
            cleanupIfNeeded();
            Entry entry = new Entry(fresh, clock.instant().plus(ttlOf.apply(fresh)));
            cache.put(key, entry);
            // 계산 시작 뒤 무효화가 있었으면(put 직전 포함 — invalidateIf는 락 없음) 방금 넣은 항목을 되돌린다
            // ponytail: 인스턴스 전역 세대 — 무효화 순간 진행 중이던 다른 키 결과도 한 번 캐시 생략, 무효화가 잦아지면 키별 세대로
            if (generation.get() != gen) {
                cache.remove(key, entry);
            }
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    // 조건에 맞는 키만 즉시 제거 — 사용자 단위 무효화(체결 직후 stale 방지)용. 전체 clear는 제공하지 않는다
    public void invalidateIf(Predicate<Object> keyFilter) {
        generation.incrementAndGet(); // removeIf 전에 세대를 올려 진행 중 계산 결과의 put을 막는다
        cache.keySet().removeIf(keyFilter);
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
