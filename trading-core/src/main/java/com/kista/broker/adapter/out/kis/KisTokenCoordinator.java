package com.kista.broker.adapter.out.kis;

import com.kista.broker.adapter.out.internal.TokenCoordinator;
import com.kista.sharedkernel.TimeZones;
import com.kista.broker.application.port.output.BrokerTokenCachePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

// KIS 계좌 토큰 조정 — JVM-local 더블체크락 + PostgreSQL broker_tokens 캐시.
// Toss(TossDistributedTokenCoordinator, Redis 분산 lease+fencing)와 같은 TokenCoordinator 계약을
// 구현하지만 메커니즘은 다르다 — KIS 재발급은 이전 토큰을 무효화하지 않아(비파괴적) 인스턴스 간
// 분산 조정이 불필요하다. 이 비대칭은 의도된 설계다(docs/agents/toss-api.md "토큰·인증", docs/agents/modules/broker.md adapter/out/internal 항목 참고).
@Component
class KisTokenCoordinator implements TokenCoordinator {

    // 계좌별 락 — 같은 계좌 동시 호출이 토큰을 N번 발급하는 것 방지
    private final ConcurrentMap<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final BrokerTokenCachePort cachePort;

    @Autowired
    KisTokenCoordinator(BrokerTokenCachePort cachePort) {
        this.cachePort = cachePort;
    }

    @Override
    public String obtain(UUID accountId, TokenIssuer issuer) {
        // 1차 조회 — 락 없이 빠른 경로
        Optional<String> cached = cachePort.findValidToken(accountId, threshold());
        if (cached.isPresent()) {
            return cached.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 2차 조회(double-check) — 다른 스레드가 이미 발급했을 수 있음
            Optional<String> doubleChecked = cachePort.findValidToken(accountId, threshold());
            if (doubleChecked.isPresent()) {
                return doubleChecked.get();
            }
            return issueAndCache(accountId, issuer);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public RecoveredToken recover(UUID accountId, String rejectedToken, TokenIssuer issuer) {
        // accountId+rejectedToken 정확 일치 시만 무효화 — 이미 다른 스레드가 재발급했다면 no-op
        cachePort.invalidateToken(accountId, rejectedToken, OffsetDateTime.now(TimeZones.KST).minusHours(1));
        // 무효화로 캐시가 miss 상태가 되어 obtain이 신규 발급 경로로 재진입한다.
        // KIS 재발급은 파괴적이지 않아 재사용 여부를 판별할 필요가 없어 항상 freshlyIssued=true로 보고한다.
        return new RecoveredToken(obtain(accountId, issuer), true);
    }

    private String issueAndCache(UUID accountId, TokenIssuer issuer) {
        IssuedToken issued = issuer.issue();
        OffsetDateTime expiresAt = OffsetDateTime.now(TimeZones.KST).plusSeconds(issued.expiresInSeconds());
        cachePort.saveToken(accountId, issued.accessToken(), expiresAt);
        return issued.accessToken();
    }

    // 만료 1분 전부터 무효 처리 — 경계값 만료 오류(EGW00123) 방지
    private static OffsetDateTime threshold() {
        return OffsetDateTime.now(TimeZones.KST).plusMinutes(1);
    }
}
