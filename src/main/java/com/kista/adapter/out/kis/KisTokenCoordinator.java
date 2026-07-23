package com.kista.adapter.out.kis;

import com.kista.adapter.out.broker.DoubleCheckedTokenCache;
import com.kista.adapter.out.broker.TokenCoordinator;
import com.kista.common.TimeZones;
import com.kista.domain.port.out.BrokerTokenCachePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

// KIS 계좌 토큰 조정 — JVM-local 더블체크락(DoubleCheckedTokenCache) + PostgreSQL broker_tokens 캐시.
// Toss(TossDistributedTokenCoordinator, Redis 분산 lease+fencing)와 같은 TokenCoordinator 계약을
// 구현하지만 메커니즘은 다르다 — KIS 재발급은 이전 토큰을 무효화하지 않아(비파괴적) 인스턴스 간
// 분산 조정이 불필요하다. 이 비대칭은 의도된 설계다(docs/agents/architecture.md "브로커별 토큰 조정 메커니즘은 다르지만 계약은 공유한다" 참고).
@Component
class KisTokenCoordinator implements TokenCoordinator {

    private final DoubleCheckedTokenCache tokenCache = new DoubleCheckedTokenCache();
    private final BrokerTokenCachePort cachePort;

    @Autowired
    KisTokenCoordinator(BrokerTokenCachePort cachePort) {
        this.cachePort = cachePort;
    }

    @Override
    public String obtain(UUID accountId, TokenIssuer issuer) {
        return tokenCache.getOrFetch(cachePort, accountId, KisTokenCoordinator::threshold,
                () -> issueAndCache(accountId, issuer));
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
