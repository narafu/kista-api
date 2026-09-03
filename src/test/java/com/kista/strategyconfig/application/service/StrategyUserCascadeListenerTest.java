package com.kista.strategyconfig.application.service;

import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.user.application.event.UserDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StrategyUserCascadeListenerTest {

    @Mock
    private StrategyPort strategyPort;

    @Test
    void onUserDeleted_사용자ID로_전략을_soft_delete한다() {
        StrategyUserCascadeListener listener = new StrategyUserCascadeListener(strategyPort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(strategyPort).deleteByUserId(userId);
    }
}
