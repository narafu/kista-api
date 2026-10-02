package com.kista.web;

import com.kista.admin.domain.model.AdminBrokerCredentialException;
import com.kista.admin.domain.model.AdminBrokerRateLimitException;
import com.kista.admin.domain.model.TradingPolicyUnavailableException;
import com.kista.finance.domain.model.MonthlyClosing;
import com.kista.platform.web.ErrorCode;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import com.kista.user.domain.model.User;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void monthClosedException_mapsTo409() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);

        var detail = handler.handleAll(new MonthlyClosing.MonthClosedException("2026-09"));

        assertThat(detail.getStatus()).isEqualTo(409);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void asyncRequestNotUsableException_alreadyCommitted_skipsStatusChange() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        handler.handleAsyncLifecycle(
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush"), response);

        verifyNoInteractions(eventPublisher);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void asyncRequestTimeoutException_notCommitted_sets503WithoutBody() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(false);

        handler.handleAsyncLifecycle(new AsyncRequestTimeoutException(), response);

        verifyNoInteractions(eventPublisher);
        verify(response).setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void noResourceFoundException_mapsTo404_withoutErrorLog() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);

        var detail = handler.handleAll(new NoResourceFoundException(HttpMethod.GET, "actuator/heapdump", ""));

        assertThat(detail.getStatus()).isEqualTo(404);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void clientDisconnect_brokenPipeMessage_skipsErrorLog() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);

        var detail = handler.handleAll(
                new HttpMessageNotWritableException("Could not write JSON", new IOException("Broken pipe")));

        assertThat(detail.getStatus()).isEqualTo(503);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void clientDisconnect_rawClientAbortException_skipsErrorLog() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);

        // HttpMessageNotWritableException으로 감싸이지 않고 raw로 전파되는 경로 + FQCN 문자열 매칭 분기 커버
        var detail = handler.handleAll(new ClientAbortException(new IOException("Connection reset by peer")));

        assertThat(detail.getStatus()).isEqualTo(503);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void httpMessageNotWritable_serializationFailure_publishesErrorEventAnd500() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);

        // 원인이 IOException이어도 broken pipe/connection reset이 아니면(Jackson 매핑 오류 등) 실제 결함으로 취급
        var detail = handler.handleAll(
                new HttpMessageNotWritableException("Could not write JSON",
                        new IOException("No serializer found for class com.example.Foo")));

        assertThat(detail.getStatus()).isEqualTo(500);
        // 500 경로는 admin 포트가 아니라 AppErrorRaisedEvent를 발행한다(저장은 admin 리스너 담당)
        ArgumentCaptor<AppErrorRaisedEvent> captor = ArgumentCaptor.forClass(AppErrorRaisedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo("HttpMessageNotWritableException");
        assertThat(captor.getValue().context()).containsEntry("caller", "GlobalExceptionHandler");
    }

    @Test
    void handleAll_mapped4xxWithoutSystemCause_doesNotPublishErrorEvent() {
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(eventPublisher);
        IllegalArgumentException ex = new IllegalArgumentException("예수금이 부족합니다");

        handler.handleAll(ex);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void monthClosed_hasCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        var p = handler.handleAll(new MonthlyClosing.MonthClosedException("2026-08"));
        assertThat(p.getStatus()).isEqualTo(409);
        assertThat(p.getProperties()).containsEntry("code", ErrorCode.MONTH_CLOSED.name());
        assertThat(p.getDetail()).endsWith("다시 시도해주세요.");
    }

    @Test
    void adminBrokerExceptions_shareTradingCoreCodes() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        assertThat(handler.handleAll(new AdminBrokerCredentialException()).getProperties())
                .containsEntry("code", ErrorCode.BROKER_CREDENTIAL_INVALID.name());
        assertThat(handler.handleAll(new AdminBrokerRateLimitException()).getProperties())
                .containsEntry("code", ErrorCode.BROKER_RATE_LIMITED.name());
    }

    @Test
    void tradingPolicyUnavailable_hasCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        var p = handler.handleAll(new TradingPolicyUnavailableException(new RuntimeException("I/O error on GET http://internal:8081")));
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", ErrorCode.TRADING_CORE_UNAVAILABLE.name());
        assertThat(p.getDetail()).doesNotContain("http");
    }

    @Test
    void cooldown_keepsRetryAfterAndAddsCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        Instant retryAfter = Instant.parse("2026-10-03T00:00:00Z");
        var res = handler.handleCooldown(new User.CooldownException(retryAfter));
        assertThat(res.getStatusCode().value()).isEqualTo(429);
        assertThat(res.getBody().getProperties())
                .containsEntry("code", ErrorCode.COOLDOWN_ACTIVE.name())
                .containsEntry("retryAfter", retryAfter.toString());
        assertThat(res.getBody().getDetail()).isEqualTo("재신청 대기 중입니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    void securityException_hasAccessDeniedCode() {
        var handler = new GlobalExceptionHandler(mock(ApplicationEventPublisher.class));
        assertThat(handler.handleAll(new SecurityException("접근 권한이 없습니다")).getProperties())
                .containsEntry("code", ErrorCode.ACCESS_DENIED.name());
    }
}
