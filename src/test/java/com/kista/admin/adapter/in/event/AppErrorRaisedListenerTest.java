package com.kista.admin.adapter.in.event;

import com.kista.admin.application.port.output.AppErrorLogPort;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppErrorRaisedListenerTest {

    @Mock AppErrorLogPort appErrorLogPort;
    @InjectMocks AppErrorRaisedListener listener;

    @Test
    void on_savesEventFieldsThroughFourArgPort() {
        var event = new AppErrorRaisedEvent("IllegalStateException", "boom", "at foo()", Map.of("caller", "GlobalExceptionHandler"));

        listener.on(event);

        verify(appErrorLogPort).save("IllegalStateException", "boom", "at foo()", Map.of("caller", "GlobalExceptionHandler"));
    }
}
