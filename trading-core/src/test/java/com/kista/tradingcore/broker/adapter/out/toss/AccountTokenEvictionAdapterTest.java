package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.tradingcore.broker.application.port.output.BrokerTokenCachePort;
import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("계좌 토큰 제거 — KIS DB + Toss Redis")
class AccountTokenEvictionAdapterTest {

    private final BrokerTokenCachePort cachePort = mock(BrokerTokenCachePort.class);
    private final TossTokenStore tokenStore = mock(TossTokenStore.class);
    private final AccountTokenEvictionAdapter adapter = new AccountTokenEvictionAdapter(cachePort, tokenStore);

    @Test
    @DisplayName("KIS 토큰을 지우고 계좌마다 Toss scope를 무효화하며, Redis 실패는 다음 계좌 처리를 막지 않는다")
    void evictsKisAndEveryTossScope() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        doThrow(new TossApiException("Toss token Redis 무효화 실패", null))
                .when(tokenStore).invalidate("account:" + failing);

        adapter.evict(List.of(failing, next));

        verify(cachePort).deleteByAccountIds(List.of(failing, next));
        verify(tokenStore).invalidate("account:" + failing);
        verify(tokenStore).invalidate("account:" + next);
    }

    @Test
    @DisplayName("호출자 트랜잭션이 있으면 Toss 무효화를 커밋 뒤로 미룬다")
    void defersTossInvalidationUntilCommit() {
        UUID accountId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.evict(List.of(accountId));

            verify(cachePort).deleteByAccountIds(List.of(accountId));
            verify(tokenStore, never()).invalidate(any());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            verify(tokenStore).invalidate("account:" + accountId);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
