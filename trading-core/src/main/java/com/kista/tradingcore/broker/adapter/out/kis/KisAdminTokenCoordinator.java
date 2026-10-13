package com.kista.tradingcore.broker.adapter.out.kis;

import com.kista.shared.platform.crypto.Sha256;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator.IssuedToken;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator.RecoveredToken;
import com.kista.tradingcore.broker.adapter.out.internal.TokenCoordinator.TokenIssuer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

// KIS 관리자(시세 전용) 토큰 — 계좌가 없어 broker_tokens(accounts FK)를 못 쓰고 Redis에 캐시한다.
// KIS는 토큰을 24시간 재사용해야 하고 발급은 1분당 1회(EGW00133)로 제한되므로, 이 캐시가 재발급 폭주를 막는다.
// 키가 앱키 해시를 포함하므로(앱키 교체 시 옛 토큰 재사용 방지) accountId 기준 TokenCoordinator 계약은 구현하지 않는다.
@Slf4j
@Component
class KisAdminTokenCoordinator {

    // 관리자 토큰 식별용 nil UUID — 계좌 id는 v7이라 충돌하지 않는다. KisAuthApi가 이 id면 이 코디네이터로 라우팅
    static final UUID ADMIN_TOKEN_ID = new UUID(0L, 0L);
    private static final String KEY_PREFIX = "kis:admin:token:"; // Redis 키 접두사 — 뒤에 sha256(appKey) 앞 8자
    private static final long EXPIRY_MARGIN_SECONDS = 60; // 만료 1분 전 무효화 — KisTokenCoordinator threshold와 동일

    private final StringRedisTemplate redis;
    private final ReentrantLock lock = new ReentrantLock(); // JVM 내 동시 발급 방지
    // Redis 장애 대비 JVM 메모리 폴백 — 마지막 발급 토큰(키는 앱키별 구분)과 만료 시각
    private volatile MemoryToken memory;

    private record MemoryToken(String key, String token, Instant expiresAt) {}

    @Autowired
    KisAdminTokenCoordinator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    String obtain(String appKey, TokenIssuer issuer) {
        String key = keyOf(appKey);
        String cached = read(key);
        if (cached != null) return cached;
        lock.lock();
        try {
            // double-check — 다른 스레드가 이미 발급했을 수 있음
            cached = read(key);
            return cached != null ? cached : issueAndStore(key, issuer);
        } finally {
            lock.unlock();
        }
    }

    RecoveredToken recover(String appKey, String rejectedToken, TokenIssuer issuer) {
        String key = keyOf(appKey);
        lock.lock();
        try {
            // 저장값이 거절된 토큰과 같을 때만 삭제 — 이미 다른 스레드가 재발급했다면 그 토큰을 재사용
            if (rejectedToken != null && rejectedToken.equals(read(key))) {
                MemoryToken m = memory;
                if (m != null && rejectedToken.equals(m.token())) memory = null;
                try {
                    redis.delete(key);
                } catch (Exception e) {
                    log.warn("KIS 관리자 토큰 삭제 실패: {}", e.getMessage());
                }
            }
            String cached = read(key);
            // 삭제가 실패해 거절된 토큰이 그대로 읽히면 재사용하지 않고 재발급
            boolean reusable = cached != null && !cached.equals(rejectedToken);
            return new RecoveredToken(reusable ? cached : issueAndStore(key, issuer), true);
        } finally {
            lock.unlock();
        }
    }

    private static String keyOf(String appKey) {
        return KEY_PREFIX + Sha256.hex(appKey).substring(0, 8);
    }

    private String issueAndStore(String key, TokenIssuer issuer) {
        IssuedToken issued = issuer.issue();
        long ttl = Math.max(1, issued.expiresInSeconds() - EXPIRY_MARGIN_SECONDS);
        memory = new MemoryToken(key, issued.accessToken(), Instant.now().plusSeconds(ttl));
        try {
            redis.opsForValue().set(key, issued.accessToken(), Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("KIS 관리자 토큰 Redis 저장 실패(토큰은 반환): {}", e.getMessage());
        }
        return issued.accessToken();
    }

    // Redis 미스는 null, Redis 장애면 유효한 메모리 토큰으로 폴백(장애 중 매 호출 재발급·EGW00133 방지)
    private String read(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("KIS 관리자 토큰 Redis 조회 실패 — 메모리 폴백: {}", e.getMessage());
            MemoryToken m = memory;
            return m != null && m.key().equals(key) && Instant.now().isBefore(m.expiresAt()) ? m.token() : null;
        }
    }
}
