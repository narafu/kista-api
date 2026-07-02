package com.kista.adapter.out.broker;

import com.kista.domain.port.out.BrokerTokenCachePort;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

// 계좌별 토큰 발급 double-checked locking 템플릿 — KIS/Toss AuthApi 공용
// 1차 캐시 조회(락 없음) → miss 시 accountId별 락 → 2차 double-check → 신규 발급
public final class DoubleCheckedTokenCache {

    // 계좌별 락 — 같은 계좌 동시 호출이 토큰을 N번 발급하는 것 방지
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    // threshold: 만료 경계 여유 시각 공급자 (브로커별 상이 — KIS 1분, Toss 5분)
    // fetcher: 캐시 miss 시 실제 발급 + 캐시 저장을 수행하고 토큰 반환
    public String getOrFetch(BrokerTokenCachePort cachePort, UUID accountId,
                             Supplier<OffsetDateTime> threshold, Supplier<String> fetcher) {
        // 1차 조회 — 락 없이 빠른 경로
        Optional<String> cached = cachePort.findValidToken(accountId, threshold.get());
        if (cached.isPresent()) {
            return cached.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 2차 조회 (double-check) — 다른 스레드가 이미 발급했을 수 있음
            return cachePort.findValidToken(accountId, threshold.get()).orElseGet(fetcher);
        } finally {
            lock.unlock();
        }
    }
}
