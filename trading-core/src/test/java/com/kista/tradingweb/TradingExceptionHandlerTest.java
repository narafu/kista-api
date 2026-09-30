package com.kista.tradingweb;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.sharedkernel.AppErrorRaisedEvent;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.OrderCancelException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TradingExceptionHandlerTest {

    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TradingExceptionHandler handler = new TradingExceptionHandler(eventPublisher);

    @Test
    void brokerCredentialException_mapsTo422() {
        var detail = handler.handleTradingCoreExceptions(new BrokerCredentialException());
        assertThat(detail.getStatus()).isEqualTo(422);
    }

    @Test
    void brokerRateLimitException_mapsTo429() {
        var detail = handler.handleTradingCoreExceptions(new BrokerRateLimitException());
        assertThat(detail.getStatus()).isEqualTo(429);
    }

    @Test
    void manualTradingException_mapsTo409() {
        var detail = handler.handleTradingCoreExceptions(new ManualTradingException("오늘 이미 주문이 존재합니다"));
        assertThat(detail.getStatus()).isEqualTo(409);
    }

    @Test
    void orderCancelException_mapsTo409() {
        var detail = handler.handleTradingCoreExceptions(new OrderCancelException("PLACED 상태가 아닙니다"));
        assertThat(detail.getStatus()).isEqualTo(409);
    }

    @Test
    void privacyTradeConflictException_mapsTo409() {
        var detail = handler.handleTradingCoreExceptions(new PrivacyTradeConflictException("기준 매매표 충돌"));
        assertThat(detail.getStatus()).isEqualTo(409);
    }

    @Test
    void duplicateAccountException_mapsTo409() {
        var detail = handler.handleTradingCoreExceptions(new Account.DuplicateAccountException("74420614"));
        assertThat(detail.getStatus()).isEqualTo(409);
    }

    @Test
    void kisApiException_mapsTo503AndPublishesErrorReport() {
        var detail = handler.handleBrokerApiException(new KisApiException("KIS API 연결 오류", null));

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getTitle()).isEqualTo("KIS API Error");
        ArgumentCaptor<AppErrorRaisedEvent> captor = ArgumentCaptor.forClass(AppErrorRaisedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo("KisApiException");
        assertThat(captor.getValue().message()).isEqualTo("KIS API 연결 오류");
        assertThat(captor.getValue().context()).containsEntry("caller", "TradingExceptionHandler");
        assertThat(captor.getValue().stackTrace()).contains("KisApiException");
    }

    @Test
    void tossApiException_mapsTo503AndPublishesErrorReport() {
        var detail = handler.handleBrokerApiException(new TossApiException("invalid-token", null));

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getTitle()).isEqualTo("Toss API Error");
        verify(eventPublisher).publishEvent(any(AppErrorRaisedEvent.class));
    }

    // 리스너(스트림 발행 등)가 예외를 던져도 원래 응답 매핑은 유지된다
    @Test
    void kisApiException_publishFails_stillMapsTo503() {
        doThrow(new RuntimeException("발행 실패")).when(eventPublisher).publishEvent(any(AppErrorRaisedEvent.class));

        var detail = handler.handleBrokerApiException(new KisApiException("KIS API 연결 오류", null));

        assertThat(detail.getStatus()).isEqualTo(503);
    }

    // GENERIC_MAPPINGS에 있는 4xx 예외는 오류 보고 없이 매핑만 한다
    @Test
    void illegalArgument_mapsTo400WithoutErrorReport() {
        var detail = handler.handleGeneric(new IllegalArgumentException("잘못된 요청"));

        assertThat(detail.getStatus()).isEqualTo(400);
        verifyNoInteractions(eventPublisher);
    }

    // 매핑 없는 예외는 500 + 오류 보고
    @Test
    void unmappedException_mapsTo500AndPublishesErrorReport() {
        var detail = handler.handleGeneric(new RuntimeException("예상 못 한 오류"));

        assertThat(detail.getStatus()).isEqualTo(500);
        verify(eventPublisher).publishEvent(any(AppErrorRaisedEvent.class));
    }
}
