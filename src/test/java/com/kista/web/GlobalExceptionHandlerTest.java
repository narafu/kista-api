package com.kista.web;

import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.admin.application.port.output.AppErrorLogPort;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void monthClosedException_mapsTo409() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        var detail = handler.handleAll(new MonthlyClosing.MonthClosedException("2026-09"));

        assertThat(detail.getStatus()).isEqualTo(409);
        verifyNoInteractions(appErrorLogPort);
    }

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
    void noResourceFoundException_mapsTo404_withoutErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        var detail = handler.handleAll(new NoResourceFoundException(HttpMethod.GET, "actuator/heapdump", ""));

        assertThat(detail.getStatus()).isEqualTo(404);
        verifyNoInteractions(appErrorLogPort);
    }

    @Test
    void clientDisconnect_brokenPipeMessage_skipsErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        var detail = handler.handleAll(
                new HttpMessageNotWritableException("Could not write JSON", new IOException("Broken pipe")));

        assertThat(detail.getStatus()).isEqualTo(503);
        verifyNoInteractions(appErrorLogPort);
    }

    @Test
    void clientDisconnect_rawClientAbortException_skipsErrorLog() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        // HttpMessageNotWritableException으로 감싸이지 않고 raw로 전파되는 경로 + FQCN 문자열 매칭 분기 커버
        var detail = handler.handleAll(new ClientAbortException(new IOException("Connection reset by peer")));

        assertThat(detail.getStatus()).isEqualTo(503);
        verifyNoInteractions(appErrorLogPort);
    }

    @Test
    void httpMessageNotWritable_serializationFailure_savesErrorLogAnd500() {
        AppErrorLogPort appErrorLogPort = mock(AppErrorLogPort.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(appErrorLogPort);

        // 원인이 IOException이어도 broken pipe/connection reset이 아니면(Jackson 매핑 오류 등) 실제 결함으로 취급
        var detail = handler.handleAll(
                new HttpMessageNotWritableException("Could not write JSON",
                        new IOException("No serializer found for class com.example.Foo")));

        assertThat(detail.getStatus()).isEqualTo(500);
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
