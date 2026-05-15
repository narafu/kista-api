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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisExecutionAdapter 체결 조회 검증")
class KisExecutionAdapterTest {

    @Mock KisHttpClient kisHttpClient;
    @InjectMocks KisExecutionAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://api.test.com", "key", "secret"
    );
    private static final LocalDate DATE = LocalDate.of(2024, 6, 15);
    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            StrategyType.INFINITE, StrategyStatus.ACTIVE,
            null, null, "SOXL", "AMS", Instant.now(), Instant.now()
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("null 응답: 빈 리스트 반환")
    void getExecutions_nullResponse_returnsEmptyList() {
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(null);

        List<Execution> result = adapter.getExecutions(DATE, ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("output=null 응답: 빈 리스트 반환")
    void getExecutions_nullOutput_returnsEmptyList() {
        KisExecutionAdapter.ExecutionListResponse response = new KisExecutionAdapter.ExecutionListResponse(null);
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(response);

        List<Execution> result = adapter.getExecutions(DATE, ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("SELN_BYOV_CLS=01: SELL 방향 반환")
    void getExecutions_selnByovCls01_returnsSell() {
        KisExecutionAdapter.ExecutionListResponse response = new KisExecutionAdapter.ExecutionListResponse(
                List.of(new KisExecutionAdapter.ExecutionListResponse.OutputItem(
                        "SOXL", "01", "5", "25.00", "125.00", "ORD001"
                ))
        );
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(response);

        List<Execution> result = adapter.getExecutions(DATE, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).direction()).isEqualTo(Order.OrderDirection.SELL);
    }

    @Test
    @DisplayName("SELN_BYOV_CLS≠01: BUY 방향, 필드 정상 파싱")
    void getExecutions_otherSelnByovCls_returnsBuyWithFields() {
        KisExecutionAdapter.ExecutionListResponse response = new KisExecutionAdapter.ExecutionListResponse(
                List.of(new KisExecutionAdapter.ExecutionListResponse.OutputItem(
                        "SOXL", "02", "10", "30.50", "305.00", "ORD002"
                ))
        );
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(response);

        List<Execution> result = adapter.getExecutions(DATE, ACCOUNT);

        assertThat(result).hasSize(1);
        Execution e = result.get(0);
        assertThat(e.direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(e.qty()).isEqualTo(10);
        assertThat(e.price()).isEqualByComparingTo("30.50");
        assertThat(e.amountUsd()).isEqualByComparingTo("305.00");
        assertThat(e.kisOrderId()).isEqualTo("ORD002");
        assertThat(e.symbol()).isEqualTo("SOXL");
        assertThat(e.tradeDate()).isEqualTo(DATE);
    }

    @Test
    @DisplayName("빈 문자열 필드: qty=0, price=0, amountUsd=0 안전 파싱")
    void getExecutions_blankFields_parsedSafely() {
        KisExecutionAdapter.ExecutionListResponse response = new KisExecutionAdapter.ExecutionListResponse(
                List.of(new KisExecutionAdapter.ExecutionListResponse.OutputItem(
                        "SOXL", "02", "", "", "", "ORD003"
                ))
        );
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(response);

        List<Execution> result = adapter.getExecutions(DATE, ACCOUNT);

        assertThat(result).hasSize(1);
        Execution e = result.get(0);
        assertThat(e.qty()).isEqualTo(0);
        assertThat(e.price()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(e.amountUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
