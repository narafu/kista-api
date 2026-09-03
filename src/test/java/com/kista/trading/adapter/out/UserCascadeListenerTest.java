package com.kista.trading.adapter.out;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.trading.application.port.output.CyclePositionPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserCascadeListenerTest {

    @Mock CyclePositionPort cyclePositionPort;
    @Mock StrategyCyclePort strategyCyclePort;

    @Test
    void onUserDeleted_deletesCyclePositionAndStrategyCycle() {
        UserCascadeListener listener = new UserCascadeListener(cyclePositionPort, strategyCyclePort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(cyclePositionPort).deleteByUserId(userId);
        verify(strategyCyclePort).deleteByUserId(userId);
    }
}
