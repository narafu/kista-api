package com.kista.adapter.out.kis;

import com.kista.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisProfitAdapter 기간손익 조회 검증")
class KisProfitAdapterTest {

    @Mock KisHttpClient kisHttpClient;
    @InjectMocks KisProfitAdapter adapter;

    private static final KisProperties PROPS = new KisProperties(
            "https://api.test.com", "key", "secret", "12345678", "01"
    );
    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            Strategy.INFINITE, StrategyStatus.ACTIVE,
            null, null, "SOXL", "AMS", Instant.now(), Instant.now()
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("API 응답 정상 시 기간손익 목록과 요약 반환")
    void getPeriodProfit_returnsResult_whenApiSucceeds() {
        KisProfitAdapter.ProfitResponse response = new KisProfitAdapter.ProfitResponse(
                List.of(new KisProfitAdapter.ProfitResponse.Output1(
                        "20240615", "SOXL", "5", "20.00", "25.00", "25.00", "25.0", "NASD"
                )),
                new KisProfitAdapter.ProfitResponse.Output2("125.00", "25.0")
        );
        when(kisHttpClient.get(anyString(), any(), any(), eq(KisProfitAdapter.ProfitResponse.class)))
                .thenReturn(response);

        PeriodProfitResult result = adapter.getPeriodProfit(
                ACCOUNT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).symbol()).isEqualTo("SOXL");
        assertThat(result.items().get(0).qty()).isEqualTo(5);
        assertThat(result.totalRealizedProfit()).isEqualByComparingTo("125.00");
        assertThat(result.totalReturnRate()).isEqualByComparingTo("25.0");
    }

    @Test
    @DisplayName("null 응답 시 빈 목록과 0 반환")
    void getPeriodProfit_returnsEmpty_whenApiReturnsNull() {
        when(kisHttpClient.get(anyString(), any(), any(), eq(KisProfitAdapter.ProfitResponse.class)))
                .thenReturn(null);

        PeriodProfitResult result = adapter.getPeriodProfit(
                ACCOUNT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        assertThat(result.items()).isEmpty();
        assertThat(result.totalRealizedProfit()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TR_ID TTTS3039R로 조회")
    void getPeriodProfit_usesTrId() {
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(null);

        adapter.getPeriodProfit(ACCOUNT, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        verify(kisHttpClient).buildHeaders(eq("TTTS3039R"), eq(ACCOUNT));
    }
}
