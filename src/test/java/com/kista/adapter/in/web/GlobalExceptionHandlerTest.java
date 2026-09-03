package com.kista.adapter.in.web;

import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.domain.port.out.AppErrorLogPort;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void handleAll_mapped4xxWithKisApiExceptionCause_savesErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);
        KisApiException cause = new KisApiException("초당 거래건수를 초과하였습니다", null);
        ManualTradingException ex = new ManualTradingException("증권사 API 조회에 실패했습니다", cause);

        handler.handleAll(ex);

        verify(appErrorLogPort).save(any(Exception.class), anyString());
    }

    @Test
    void handleAll_mapped4xxWithTossApiExceptionCause_savesErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);
        TossApiException cause = new TossApiException("invalid-token", null);
        ManualTradingException ex = new ManualTradingException("증권사 API 조회에 실패했습니다", cause);

        handler.handleAll(ex);

        verify(appErrorLogPort).save(any(Exception.class), anyString());
    }

    @Test
    void handleAll_mapped4xxWithoutSystemCause_doesNotSaveErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);
        ManualTradingException ex = new ManualTradingException("예수금이 부족합니다");

        handler.handleAll(ex);

        verifyNoInteractions(appErrorLogPort);
    }
}
