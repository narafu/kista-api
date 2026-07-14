package com.kista.adapter.in.web;

import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalExceptionHandlerTest {

    @Test
    void asyncRequestNotUsableException_is_not_saved_as_app_error() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        var problem = handler.handleAll(new AsyncRequestNotUsableException("ServletOutputStream failed to flush"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        verifyNoInteractions(appErrorLogPort);
    }
}
