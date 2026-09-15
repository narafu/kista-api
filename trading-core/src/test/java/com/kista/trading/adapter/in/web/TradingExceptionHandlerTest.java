package com.kista.trading.adapter.in.web;

import com.kista.account.domain.model.Account;
import com.kista.broker.domain.model.BrokerCredentialException;
import com.kista.broker.domain.model.BrokerRateLimitException;
import com.kista.broker.domain.model.kis.KisApiException;
import com.kista.broker.domain.model.toss.TossApiException;
import com.kista.privacy.domain.model.PrivacyTradeConflictException;
import com.kista.trading.domain.model.ManualTradingException;
import com.kista.trading.domain.model.OrderCancelException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingExceptionHandlerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<RestClient> internalApiRestClientProvider = mock(ObjectProvider.class);
    private final RestClient internalApiRestClient = mock(RestClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private final TradingExceptionHandler handler = new TradingExceptionHandler(internalApiRestClientProvider);

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
    void kisApiException_mapsTo503AndReportsToInternalApi() {
        when(internalApiRestClientProvider.getIfAvailable()).thenReturn(internalApiRestClient);

        var detail = handler.handleKisApiException(new KisApiException("KIS API 연결 오류", null));

        assertThat(detail.getStatus()).isEqualTo(503);
        verify(internalApiRestClient).post();
    }

    @Test
    void tossApiException_mapsTo503AndReportsToInternalApi() {
        when(internalApiRestClientProvider.getIfAvailable()).thenReturn(internalApiRestClient);

        var detail = handler.handleTossApiException(new TossApiException("invalid-token", null));

        assertThat(detail.getStatus()).isEqualTo(503);
        verify(internalApiRestClient).post();
    }

    @Test
    void kisApiException_internalApiCallFails_stillMapsTo503() {
        RestClient failingClient = mock(RestClient.class);
        when(failingClient.post()).thenThrow(new RuntimeException("연결 실패"));
        when(internalApiRestClientProvider.getIfAvailable()).thenReturn(failingClient);

        var detail = handler.handleKisApiException(new KisApiException("KIS API 연결 오류", null));

        assertThat(detail.getStatus()).isEqualTo(503);
    }

    // @WebMvcTest 슬라이스처럼 RestClient 빈이 컨텍스트에 없는 경우(getIfAvailable()==null) —
    // 생성 자체는 성공하고 호출 시점에만 null-safe하게 스킵되는지 검증
    @Test
    void kisApiException_noRestClientBeanAvailable_stillMapsTo503() {
        when(internalApiRestClientProvider.getIfAvailable()).thenReturn(null);

        var detail = handler.handleKisApiException(new KisApiException("KIS API 연결 오류", null));

        assertThat(detail.getStatus()).isEqualTo(503);
    }
}
