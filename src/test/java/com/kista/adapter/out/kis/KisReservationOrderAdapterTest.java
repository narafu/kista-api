package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisReservationOrderAdapter 취소 API 검증")
class KisReservationOrderAdapterTest {

    @Mock KisHttpClient kisHttpClient;
    @InjectMocks KisReservationOrderAdapter adapter;

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            Account.Broker.KIS
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("cancelReservationOrder: TTTT3017U + CANCEL_PATH 호출, 파라미터 정확히 전달")
    void cancelReservationOrder_sendsCorrectParameters() {
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.cancelReservationOrder("RSV-001", "20260610", ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT3017U"), eq(ACCOUNT));
        verify(kisHttpClient).post(
                eq("/uapi/overseas-stock/v1/trading/order-resv-ccnl"),
                any(), bodyCaptor.capture(), any());

        Map<?, ?> body = bodyCaptor.getValue();
        assertThat(body.get("OVRS_RSVN_ODNO")).isEqualTo("RSV-001");
        assertThat(body.get("RSVN_ORD_RCIT_DT")).isEqualTo("20260610");
        assertThat(body.get("CANO")).isEqualTo(ACCOUNT.accountNo());
        assertThat(body.get("ACNT_PRDT_CD")).isEqualTo(ACCOUNT.kisAccountType());
    }

    @Test
    @DisplayName("cancelReservationOrder: KIS 오류 → RuntimeException 전파")
    void cancelReservationOrder_kisError_propagatesException() {
        when(kisHttpClient.post(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("KIS 취소 오류"));

        assertThatThrownBy(() -> adapter.cancelReservationOrder("RSV-001", "20260610", ACCOUNT))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("KIS 취소 오류");
    }
}
