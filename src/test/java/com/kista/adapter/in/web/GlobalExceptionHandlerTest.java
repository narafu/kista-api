package com.kista.adapter.in.web;

import com.kista.domain.port.out.AppErrorLogPort;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test
    void asyncRequestNotUsableException_alreadyCommitted_skipsStatusChange() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        handler.handleAsyncLifecycle(
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush"), response);

        verifyNoInteractions(appErrorLogPort);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void asyncRequestTimeoutException_notCommitted_sets503WithoutBody() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        handler.handleAsyncLifecycle(new AsyncRequestTimeoutException(), response);

        verifyNoInteractions(appErrorLogPort);
        verify(response).setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    }
}
