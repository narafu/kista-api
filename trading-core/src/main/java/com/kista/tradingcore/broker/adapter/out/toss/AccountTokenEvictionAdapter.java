package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.application.port.output.AccountTokenEvictionPort;
import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.UUID;

// 증권사 무관 계좌 토큰 제거 — TossTokenStore가 package-private이라 toss 패키지에 둔다
@Slf4j
@Component
@RequiredArgsConstructor
class AccountTokenEvictionAdapter implements AccountTokenEvictionPort {

    private final BrokerTokenCachePort brokerTokenCachePort; // KIS broker_tokens(DB) 캐시
    private final TossTokenStore tossTokenStore; // Toss Redis canonical 저장소

    @Override
    public void evict(Collection<UUID> accountIds) {
        brokerTokenCachePort.deleteByAccountIds(accountIds);
        // Redis는 트랜잭션 밖 외부 호출 — 호출자 트랜잭션(계좌 삭제·탈퇴)이 있으면 커밋 뒤로 미룬다(롤백 시 무효화 안 함)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidateToss(accountIds);
                }
            });
        } else {
            invalidateToss(accountIds);
        }
    }

    private void invalidateToss(Collection<UUID> accountIds) {
        // 브로커 구분 없이 전 계좌에 적용 — 발급 이력 없는 scope는 Lua가 아무것도 만들지 않는다.
        // Redis 실패는 계좌 키 교체·삭제·탈퇴를 막지 않도록 삼킨다
        for (UUID accountId : accountIds) {
            try {
                tossTokenStore.invalidate(TossDistributedTokenCoordinator.accountScope(accountId));
            } catch (RuntimeException e) {
                log.warn("Toss 토큰 캐시 무효화 실패 — TTL 만료까지 옛 토큰이 남을 수 있음: accountId={}", accountId, e);
            }
        }
    }
}
