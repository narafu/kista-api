package com.kista.adapter.out.notify;

import com.kista.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramAdapterTest {

    @Mock RestTemplate restTemplate;

    TelegramAdapter adapter;

    static final TelegramProperties PROPS =
            new TelegramProperties("test-token", "chat-123");
    static final TelegramProperties EMPTY_PROPS =
            new TelegramProperties("", "chat-123");

    @BeforeEach
    void setUp() {
        adapter = new TelegramAdapter(restTemplate, PROPS);
    }

    @Test
    void notifyMarketClosed_sendsCorrectUrl() {
        adapter.notifyMarketClosed();

        verify(restTemplate).postForObject(
                contains("/bottest-token/sendMessage"),
                any(), eq(String.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyMarketClosed_bodyContainsChatId() {
        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);

        adapter.notifyMarketClosed();

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        assertThat(bodyCaptor.getValue()).containsEntry("chat_id", "chat-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_bodyContainsQtyAndAmount() {
        AccountBalance balance = new AccountBalance(0, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("5.00")); // effectiveAmt=0, usdDeposit=5.00

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyInsufficientBalance(balance);

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        String text = bodyCaptor.getValue().get("text");
        assertThat(text).contains("0주").contains("5.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyReport_bodyContainsDateAndAmounts() {
        TradingVariables vars = TradingVariables.builder()
                .averagePrice(new BigDecimal("20.00"))
                .quantity(10)
                .purchaseAmount(new BigDecimal("200.00"))
                .evaluationAmount(new BigDecimal("210.00"))
                .totalAssets(new BigDecimal("700.00"))
                .totalRounds(20)
                .currentRound(1.33)
                .unitAmount(new BigDecimal("35.00"))
                .targetProfitRate(new BigDecimal("0.20"))
                .priceOffsetRate(new BigDecimal("0.1733"))
                .usdDeposit(new BigDecimal("500.00"))
                .referencePrice(new BigDecimal("23.47"))
                .targetPrice(new BigDecimal("24.00"))
                .currentPrice(new BigDecimal("22.00"))
                .build();
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), vars, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyReport(report);

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        String text = bodyCaptor.getValue().get("text");
        assertThat(text).contains("2024-06-15")
                        .contains("66.00")
                        .contains("35.00");
    }

    @Test
    void send_withEmptyToken_skipsRestTemplateCall() {
        TelegramAdapter noTokenAdapter = new TelegramAdapter(restTemplate, EMPTY_PROPS);

        noTokenAdapter.notifyMarketClosed();

        verify(restTemplate, never()).postForObject(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyError_bodyContainsExceptionMessage() {
        Exception ex = new RuntimeException("KIS API 호출 실패");
        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);

        adapter.notifyError(ex);

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        assertThat(bodyCaptor.getValue().get("text")).contains("KIS API 호출 실패");
    }
}
