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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                new BigDecimal("5.00")); // usdDeposit=5.00

        Account account = mock(Account.class);
        when(account.symbol()).thenReturn("SOXL");
        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyInsufficientBalance(account, balance);

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

    @Test
    @SuppressWarnings("unchecked")
    void notifyStrategyChanged_bodyContainsNicknameAccountAndAction() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), null);
        Account account = new Account(UUID.randomUUID(), user.id(), "내SOXL계좌",
                "74420614", "key", "secret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyStrategyChanged(user, account, "중지");

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        String text = bodyCaptor.getValue().get("text");
        assertThat(text).contains("홍길동").contains("내SOXL계좌").contains("중지");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyTradingReport_withAccountBot_sendsToAccountChatId() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), null);
        Account account = new Account(UUID.randomUUID(), user.id(), "SOXL계좌",
                "74420614", "key", "secret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                "account-bot-token", "account-chat-456", "SOXL", "AMS", Instant.now(), Instant.now());
        TradingVariables vars = TradingVariables.builder()
                .averagePrice(new BigDecimal("20.00")).quantity(10)
                .purchaseAmount(new BigDecimal("200.00")).evaluationAmount(new BigDecimal("210.00"))
                .totalAssets(new BigDecimal("700.00")).totalRounds(20).currentRound(1.33)
                .unitAmount(new BigDecimal("35.00")).targetProfitRate(new BigDecimal("0.20"))
                .priceOffsetRate(new BigDecimal("0.1733")).usdDeposit(new BigDecimal("500.00"))
                .referencePrice(new BigDecimal("23.47")).targetPrice(new BigDecimal("24.00"))
                .currentPrice(new BigDecimal("22.00")).build();
        TradingReport report = new TradingReport(
                java.time.LocalDate.of(2024, 6, 15), vars, java.util.List.of(), java.util.List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyTradingReport(user, account, report);

        verify(restTemplate).postForObject(
                contains("/botaccount-bot-token/sendMessage"),
                bodyCaptor.capture(), eq(String.class));
        Map<String, String> body = bodyCaptor.getValue();
        assertThat(body.get("chat_id")).isEqualTo("account-chat-456");
        assertThat(body.get("text")).contains("2024-06-15").contains("SOXL계좌");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyTradingReport_withoutAccountBot_fallsBackToUserBot() {
        // 계좌 봇 미설정, 사용자 봇 설정 → 사용자 봇으로 발송
        User userWithBot = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE,
                "user-bot-token", "user-chat-789", Instant.now(), Instant.now(), null);
        Account accountNoBot = new Account(UUID.randomUUID(), userWithBot.id(), "SOXL계좌",
                "74420614", "key", "secret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());
        TradingVariables vars = TradingVariables.builder()
                .averagePrice(new BigDecimal("20.00")).quantity(10)
                .purchaseAmount(new BigDecimal("200.00")).evaluationAmount(new BigDecimal("210.00"))
                .totalAssets(new BigDecimal("700.00")).totalRounds(20).currentRound(1.33)
                .unitAmount(new BigDecimal("35.00")).targetProfitRate(new BigDecimal("0.20"))
                .priceOffsetRate(new BigDecimal("0.1733")).usdDeposit(new BigDecimal("500.00"))
                .referencePrice(new BigDecimal("23.47")).targetPrice(new BigDecimal("24.00"))
                .currentPrice(new BigDecimal("22.00")).build();
        TradingReport report = new TradingReport(
                java.time.LocalDate.of(2024, 6, 15), vars, java.util.List.of(), java.util.List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyTradingReport(userWithBot, accountNoBot, report);

        verify(restTemplate).postForObject(
                contains("/botuser-bot-token/sendMessage"),
                bodyCaptor.capture(), eq(String.class));
        assertThat(bodyCaptor.getValue().get("chat_id")).isEqualTo("user-chat-789");
    }

    @Test
    void notifyTradingReport_withoutAccountBot_skipsWithoutException() {
        User user = new User(UUID.randomUUID(), "kakao-1", "홍길동", UserStatus.ACTIVE,
                null, null, Instant.now(), Instant.now(), null);
        Account accountNoBot = new Account(UUID.randomUUID(), user.id(), "노봇계좌",
                "74420614", "key", "secret", "01",
                StrategyType.INFINITE, StrategyStatus.ACTIVE,
                null, null, "SOXL", "AMS", Instant.now(), Instant.now());
        TradingVariables vars = TradingVariables.builder()
                .averagePrice(new BigDecimal("20.00")).quantity(10)
                .purchaseAmount(new BigDecimal("200.00")).evaluationAmount(new BigDecimal("210.00"))
                .totalAssets(new BigDecimal("700.00")).totalRounds(20).currentRound(1.33)
                .unitAmount(new BigDecimal("35.00")).targetProfitRate(new BigDecimal("0.20"))
                .priceOffsetRate(new BigDecimal("0.1733")).usdDeposit(new BigDecimal("500.00"))
                .referencePrice(new BigDecimal("23.47")).targetPrice(new BigDecimal("24.00"))
                .currentPrice(new BigDecimal("22.00")).build();
        TradingReport report = new TradingReport(
                java.time.LocalDate.of(2024, 6, 15), vars, java.util.List.of(), java.util.List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        adapter.notifyTradingReport(user, accountNoBot, report);

        // 계좌 봇 미설정 시 발송 없음
        verify(restTemplate, never()).postForObject(any(), any(), any());
    }
}
