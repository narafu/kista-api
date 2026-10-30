package com.kista.tradingcore.broker.adapter.out.toss;

import com.kista.shared.kernel.Broker;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.tradingcore.broker.domain.model.CancelOutcome;
import com.kista.tradingcore.broker.domain.model.toss.TossApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

// 실제 TossHttpClient(HTTP 409 → TossApiException) + TossOrderApi.cancel(→ CancelOutcome) 끝단 연쇄 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TossOrderApi 취소 409 끝단 연쇄")
class TossOrderApiCancelConflictTest {

    private static final String BASE_URL = "http://toss.test";
    private static final String CANCEL_URL = BASE_URL + "/api/v1/orders/o1/cancel";
    private static final CancelInstruction INSTRUCTION = new CancelInstruction(StrategyTicker.SOXL, "o1");
    private static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
            UUID.randomUUID(), "cid", "csecret", "12345678901", "1", Broker.TOSS);

    @Mock TossAuthApi tossAuthApi;

    private MockRestServiceServer server;

    private TossOrderApi newApi() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        when(tossAuthApi.getToken(any(), anyString(), anyString())).thenReturn("token-0");
        return new TossOrderApi(new TossHttpClient(builder.build(), tossAuthApi, new ObjectMapper()));
    }

    @ParameterizedTest
    @CsvSource({"already-filled,ALREADY_FILLED", "already-canceled,ALREADY_CANCELED"})
    @DisplayName("HTTP 409 + 종결 error.code → CancelOutcome 반환")
    void cancel_409ClosedCode_returnsOutcome(String code, CancelOutcome expected) {
        TossOrderApi api = newApi();
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"error\":{\"code\":\"" + code + "\",\"message\":\"종결된 주문\"}}"));

        assertThat(api.cancel(INSTRUCTION, ACCOUNT)).isEqualTo(expected);
        server.verify();
    }

    @Test
    @DisplayName("HTTP 409인데 error.code가 없으면 CancelOutcome으로 삼키지 않고 예외 전파")
    void cancel_409WithoutCode_rethrows() {
        TossOrderApi api = newApi();
        server.expect(requestTo(CANCEL_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT).body("{\"error\":{\"message\":\"충돌\"}}"));

        assertThatThrownBy(() -> api.cancel(INSTRUCTION, ACCOUNT)).isInstanceOf(TossApiException.class);
        server.verify();
    }
}
