package com.kista.tradingcore.web;

import com.kista.tradingcore.support.StubBrokerApiException;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.broker.domain.model.BrokerCredentialException;
import com.kista.tradingcore.broker.domain.model.BrokerIpNotAllowedException;
import com.kista.tradingcore.broker.domain.model.BrokerPermissionDeniedException;
import com.kista.tradingcore.broker.domain.model.BrokerRateLimitException;
import com.kista.tradingcore.broker.domain.model.BrokerWithdrawalEnabledException;
import com.kista.tradingcore.broker.domain.model.BrokerApiException;
import com.kista.shared.platform.web.ErrorCode;
import com.kista.tradingcore.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.shared.kernel.AppErrorRaisedEvent;
import com.kista.tradingcore.trading.domain.model.AlreadyOrderedTodayException;
import com.kista.tradingcore.trading.domain.model.DcaManualExecutionUnsupportedException;
import com.kista.tradingcore.trading.domain.model.ManualTradingException;
import com.kista.tradingcore.trading.domain.model.ManualTradingFailedException;
import com.kista.tradingcore.trading.domain.model.OrderCancelException;
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
    void brokerCredentialException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerCredentialException());
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_CREDENTIAL_INVALID.name());
        assertThat(detail.getDetail()).isEqualTo("증권사 API 키가 유효하지 않습니다.");
    }

    @Test
    void brokerRateLimitException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerRateLimitException());
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_RATE_LIMITED.name());
        assertThat(detail.getDetail()).isEqualTo("증권사 API 호출 한도를 초과했습니다. 잠시 후 다시 시도해주세요.");
    }

    @Test
    void duplicateAccountException_hasCodeAndHidesAccountNo() {
        var detail = handler.handleTradingCoreExceptions(new Account.DuplicateAccountException("74420614-01"));
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.DUPLICATE_ACCOUNT.name());
        assertThat(detail.getDetail()).isEqualTo("이미 등록된 계좌입니다.");
    }

    @Test
    void orderCancelException_hasCode() {
        var detail = handler.handleTradingCoreExceptions(new OrderCancelException("취소 가능한 상태가 아닙니다."));
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.ORDER_NOT_CANCELLABLE.name());
    }

    @Test
    void alreadyOrderedToday_resolvesSubclassMappingBeforeParent() {
        var detail = handler.handleTradingCoreExceptions(new AlreadyOrderedTodayException());
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.ALREADY_ORDERED_TODAY.name());
        assertThat(detail.getDetail()).isEqualTo("오늘 이미 주문이 등록된 전략입니다.");
    }

    @Test
    void plainManualTradingException_hasNoCode() {
        var detail = handler.handleTradingCoreExceptions(new ManualTradingException("예수금이 부족합니다"));
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties() == null || !detail.getProperties().containsKey("code")).isTrue();
    }

    @Test
    void manualTradingFailed_mapsTo500WithFixedDetailAndNoErrorReport() {
        var detail = handler.handleTradingCoreExceptions(new ManualTradingFailedException(new RuntimeException("DB down")));
        assertThat(detail.getStatus()).isEqualTo(500);
        assertThat(detail.getDetail()).isEqualTo("주문 계산 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
        assertThat(detail.getProperties() == null || !detail.getProperties().containsKey("code")).isTrue();
        // TradingErrorEvent 경로(ManualTradingService)가 이미 보고하므로 핸들러는 재보고하지 않는다
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void kisApiException_mapsTo503AndPublishesErrorReport() {
        var detail = handler.handleBrokerApiException(new StubBrokerApiException("KIS", "KIS API 연결 오류", BrokerApiException.Conflict.NONE));

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getTitle()).isEqualTo("KIS API Error");
        // 내부 메시지는 응답에 노출하지 않고 고정 문구만 내려준다(에러 로그에는 원본 보존 — 아래 검증)
        assertThat(detail.getDetail()).isEqualTo("증권사 API 조회에 실패했습니다. 잠시 후 다시 시도해주세요.");
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_UNAVAILABLE.name());
        ArgumentCaptor<AppErrorRaisedEvent> captor = ArgumentCaptor.forClass(AppErrorRaisedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().errorType()).isEqualTo("StubBrokerApiException");
        assertThat(captor.getValue().message()).isEqualTo("KIS API 연결 오류");
        assertThat(captor.getValue().context()).containsEntry("caller", "TradingExceptionHandler");
        assertThat(captor.getValue().stackTrace()).contains("StubBrokerApiException");
    }

    @Test
    void tossApiException_mapsTo503AndPublishesErrorReport() {
        var detail = handler.handleBrokerApiException(new StubBrokerApiException("Toss", "invalid-token", BrokerApiException.Conflict.NONE));

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getTitle()).isEqualTo("Toss API Error");
        verify(eventPublisher).publishEvent(any(AppErrorRaisedEvent.class));
    }

    // 리스너(스트림 발행 등)가 예외를 던져도 원래 응답 매핑은 유지된다
    @Test
    void kisApiException_publishFails_stillMapsTo503() {
        doThrow(new RuntimeException("발행 실패")).when(eventPublisher).publishEvent(any(AppErrorRaisedEvent.class));

        var detail = handler.handleBrokerApiException(new StubBrokerApiException("KIS", "KIS API 연결 오류", BrokerApiException.Conflict.NONE));

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

    @Test
    void dcaManualExecutionUnsupported_is409WithCode() {
        var detail = handler.handleTradingCoreExceptions(new DcaManualExecutionUnsupportedException());
        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.DCA_MANUAL_EXECUTION_UNSUPPORTED.name());
        assertThat(detail.getDetail()).isEqualTo("DCA 전략은 바로 주문과 미리보기를 지원하지 않습니다.");
    }

    @Test
    void brokerIpNotAllowedException_is422WithOwnCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerIpNotAllowedException());
        assertThat(detail.getStatus()).isEqualTo(422);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_IP_NOT_ALLOWED.name());
        assertThat(detail.getDetail()).isEqualTo("거래소 API 키에 서버 IP가 허용되어 있지 않습니다.");
    }

    @Test
    void brokerPermissionDeniedException_is422WithOwnCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerPermissionDeniedException());
        assertThat(detail.getStatus()).isEqualTo(422);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_PERMISSION_DENIED.name());
        assertThat(detail.getDetail()).isEqualTo("거래소 API 키에 필요한 권한이 없습니다.");
    }

    @Test
    void withdrawalEnabledMapsTo422WithCode() {
        var detail = handler.handleTradingCoreExceptions(new BrokerWithdrawalEnabledException());
        assertThat(detail.getStatus()).isEqualTo(422);
        assertThat(detail.getProperties()).containsEntry("code", ErrorCode.BROKER_WITHDRAWAL_ENABLED.name());
        assertThat(detail.getDetail()).isEqualTo("출금 권한이 켜진 API 키는 등록할 수 없습니다.");
    }

    @Test
    void brokerRateLimitException_titleIsBrokerNeutral() {
        var detail = handler.handleTradingCoreExceptions(new BrokerRateLimitException());
        assertThat(detail.getTitle()).isEqualTo("Broker Rate Limit");
    }
}
