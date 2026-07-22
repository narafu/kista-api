package com.kista.adapter.in.web;

import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalExceptionHandlerTest {

    @Test
    void asyncRequestNotUsableException_is_handled_without_response_body() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        handler.handleAsyncLifecycle(
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush"));

        verifyNoInteractions(appErrorLogPort);
    }

    @Test
    void asyncRequestTimeoutException_is_handled_without_response_body() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        handler.handleAsyncLifecycle(new AsyncRequestTimeoutException());

        verifyNoInteractions(appErrorLogPort);
    }
}
