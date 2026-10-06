package com.kista.trading.application.service.support;

import com.kista.account.application.event.AccountDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCascadeListener 단위 테스트")
class AccountCascadeListenerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock StrategyPort strategyPort;
    @InjectMocks AccountCascadeListener listener;

    @Test
    @DisplayName("계좌 삭제 이벤트 수신 시 포지션 → 사이클 → 전략 순으로 소프트 삭제한다")
    void onAccountDeleted_deletesStrategiesByAccountId() {
        UUID accountId = UUID.randomUUID();

        listener.onAccountDeleted(new AccountDeletedEvent(accountId));

        var inOrder = inOrder(cyclePositionPort, strategyCyclePort, strategyPort);
        inOrder.verify(cyclePositionPort).deleteByAccountId(accountId);
        inOrder.verify(strategyCyclePort).deleteByAccountId(accountId);
        inOrder.verify(strategyPort).deleteByAccountId(accountId);
    }
}
