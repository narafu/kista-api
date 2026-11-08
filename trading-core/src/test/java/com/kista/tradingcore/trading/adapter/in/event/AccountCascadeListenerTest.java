package com.kista.tradingcore.trading.adapter.in.event;

import com.kista.tradingcore.account.application.event.AccountDeletedEvent;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.application.event.StrategyCapitalChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCascadeListener 단위 테스트")
class AccountCascadeListenerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyPort strategyPort;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks AccountCascadeListener listener;

    @Test
    @DisplayName("계좌 삭제 이벤트 수신 시 포지션 → 사이클 → 전략 순으로 소프트 삭제한다")
    void onAccountDeleted_deletesStrategiesByAccountId() {
        UUID accountId = UUID.randomUUID();

        listener.onAccountDeleted(new AccountDeletedEvent(accountId, UUID.randomUUID()));

        var inOrder = inOrder(cyclePositionPort, strategyCyclePort, strategyPort);
        inOrder.verify(cyclePositionPort).deleteByAccountId(accountId);
        inOrder.verify(strategyCyclePort).deleteByAccountId(accountId);
        inOrder.verify(strategyPort).deleteByAccountId(accountId);
    }

    @Test
    @DisplayName("계좌 삭제 후 소유자 stats 캐시 무효화 이벤트를 발행한다")
    void onAccountDeleted_publishesCapitalChanged() {
        UUID userId = UUID.randomUUID();

        listener.onAccountDeleted(new AccountDeletedEvent(UUID.randomUUID(), userId));

        verify(eventPublisher).publishEvent(new StrategyCapitalChangedEvent(userId));
    }
}
