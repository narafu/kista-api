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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisPortfolioAdapter 체결기준현재잔고 검증")
class KisPortfolioAdapterTest {

    @Mock KisHttpClient kisHttpClient;
    @InjectMocks KisPortfolioAdapter adapter;

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            Strategy.INFINITE, StrategyStatus.ACTIVE,
            null, null, Instant.now(), Instant.now()
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("API 응답 정상 시 종목 목록과 요약 반환")
    void getPresentBalance_returnsResult_whenApiSucceeds() {
        KisPortfolioAdapter.BalanceResponse response = new KisPortfolioAdapter.BalanceResponse(
                List.of(new KisPortfolioAdapter.BalanceResponse.Output1(
                        "SOXL", "10", "20.00", "22.00", "220.00", "20.00", "10.0", "NASD"
                )),
                new KisPortfolioAdapter.BalanceResponse.Output3("1000.00", "20.00", "2.0")
        );
        when(kisHttpClient.get(anyString(), any(), any(), eq(KisPortfolioAdapter.BalanceResponse.class)))
                .thenReturn(response);

        PresentBalanceResult result = adapter.getPresentBalance(ACCOUNT);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).symbol()).isEqualTo("SOXL");
        assertThat(result.items().get(0).qty()).isEqualTo(10);
        assertThat(result.totalAssetUsd()).isEqualByComparingTo("1000.00");
        assertThat(result.totalReturnRate()).isEqualByComparingTo("2.0");
    }

    @Test
    @DisplayName("null 응답 시 빈 목록과 0 반환")
    void getPresentBalance_returnsEmpty_whenApiReturnsNull() {
        when(kisHttpClient.get(anyString(), any(), any(), eq(KisPortfolioAdapter.BalanceResponse.class)))
                .thenReturn(null);

        PresentBalanceResult result = adapter.getPresentBalance(ACCOUNT);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalAssetUsd()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("TR_ID CTRP6504R로 조회")
    void getPresentBalance_usesTrId() {
        when(kisHttpClient.get(anyString(), any(), any(), any())).thenReturn(null);

        adapter.getPresentBalance(ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("CTRP6504R"), eq(ACCOUNT));
    }
}
