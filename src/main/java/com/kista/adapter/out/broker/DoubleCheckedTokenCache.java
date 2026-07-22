package com.kista.adapter.out.broker;

import com.kista.domain.port.out.BrokerTokenCachePort;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
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
    // 계좌별 최근 발급 세대 — 401 복구 중 갓 발급한 토큰의 전파 지연을 구분
    private final ConcurrentMap<UUID, IssuedToken> issuedTokens = new ConcurrentHashMap<>();

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
            Optional<String> doubleChecked = cachePort.findValidToken(accountId, threshold.get());
            if (doubleChecked.isPresent()) {
                return doubleChecked.get();
            }
            return fetchAndRecord(accountId, fetcher);
        } finally {
            lock.unlock();
        }
    }

    // 401 복구를 캐시 비교·최근 발급 세대 보호·조건부 무효화·재발급까지 같은 계좌 락에서 원자화
    public String recoverRejectedToken(BrokerTokenCachePort cachePort, UUID accountId, String rejectedToken,
                                       Supplier<OffsetDateTime> threshold, Duration propagationGrace,
                                       Supplier<String> fetcher) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 다른 요청이 이미 교체한 유효 토큰이면 거절된 이전 세대 대신 현재 세대를 반환한다.
            Optional<String> currentToken = cachePort.findValidToken(accountId, threshold.get());
            if (currentToken.isPresent() && !Objects.equals(currentToken.get(), rejectedToken)) {
                return currentToken.get();
            }
            // 현재 거절 토큰이 최근 발급 세대면 리소스 서버 전파 유예 동안 그대로 재사용한다.
            IssuedToken issuedToken = issuedTokens.get(accountId);
            if (issuedToken != null && issuedToken.matches(rejectedToken, propagationGrace)) {
                return rejectedToken;
            }
            // 보호 대상이 아닌 현재 거절 토큰만 무효화한 뒤 새 세대를 발급한다.
            cachePort.invalidateToken(
                    accountId, rejectedToken, OffsetDateTime.now().minusHours(1));
            return fetchAndRecord(accountId, fetcher);
        } finally {
            lock.unlock();
        }
    }

    private String fetchAndRecord(UUID accountId, Supplier<String> fetcher) {
        String token = fetcher.get();
        issuedTokens.put(accountId, new IssuedToken(token, Instant.now()));
        return token;
    }

    private record IssuedToken(String token, Instant issuedAt) {

        private boolean matches(String rejectedToken, Duration propagationGrace) {
            return Objects.equals(token, rejectedToken)
                    && issuedAt.plus(propagationGrace).isAfter(Instant.now());
        }
    }
}
