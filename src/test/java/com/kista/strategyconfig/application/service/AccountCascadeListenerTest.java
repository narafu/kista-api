package com.kista.strategyconfig.application.service;

import com.kista.account.application.event.AccountDeletedEvent;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountCascadeListener 단위 테스트")
class AccountCascadeListenerTest {

    @Mock StrategyPort strategyPort;
    @InjectMocks AccountCascadeListener listener;

    @Test
    @DisplayName("계좌 삭제 이벤트 수신 시 소속 전략을 소프트 삭제한다")
    void onAccountDeleted_deletesStrategiesByAccountId() {
        UUID accountId = UUID.randomUUID();

        listener.onAccountDeleted(new AccountDeletedEvent(accountId));

        verify(strategyPort).deleteByAccountId(accountId);
    }
}
