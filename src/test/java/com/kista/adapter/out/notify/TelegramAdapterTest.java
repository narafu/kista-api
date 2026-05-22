package com.kista.adapter.out.notify;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.user.User;
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

    // Account 10개 필드 생성자 (strategyType/strategyStatus/ticker/multiple 제거)
    private Account account(UUID userId, String nickname) {
        return new Account(UUID.randomUUID(), userId, nickname,
                "74420614", "key", "secret", "01",
                Account.Broker.KIS, Instant.now(), Instant.now());
    }

    private TradingCycle cycle(UUID accountId) {
        return new TradingCycle(UUID.randomUUID(), accountId, TradingCycle.Type.INFINITE,
                TradingCycle.Status.ACTIVE, TradingCycle.Ticker.SOXL, BigDecimal.ONE,
                null, Instant.now(), Instant.now());
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
    void notifyInsufficientBalance_bodyContainsQuantityAndAmount() {
        AccountBalance balance = new AccountBalance(0, BigDecimal.ZERO,
                new BigDecimal("5.00")); // usdDeposit=5.00

        Account acc = account(UUID.randomUUID(), "테스트");
        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyInsufficientBalance(acc, balance, TradingCycle.Ticker.SOXL);

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        String text = bodyCaptor.getValue().get("text");
        assertThat(text).contains("0주").contains("5.00");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyReport_bodyContainsDateAndAmounts() {
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
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
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null);
        Account acc = account(userId, "내SOXL계좌");
        TradingCycle tc = cycle(acc.id());

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyStrategyChanged(user, acc, tc, "중지");

        verify(restTemplate).postForObject(any(String.class), bodyCaptor.capture(), eq(String.class));
        String text = bodyCaptor.getValue().get("text");
        assertThat(text).contains("홍길동").contains("내SOXL계좌").contains("중지");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyTradingReport_withUserBot_sendsToUserChatId() {
        UUID userId = UUID.randomUUID();
        User userWithBot = new User(userId, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                "user-bot-token", "user-chat-789", null, Instant.now(), Instant.now(), null);
        Account acc = account(userId, "SOXL계좌");
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        ArgumentCaptor<Map<String, String>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.notifyTradingReport(userWithBot, acc, report);

        verify(restTemplate).postForObject(
                contains("/botuser-bot-token/sendMessage"),
                bodyCaptor.capture(), eq(String.class));
        Map<String, String> body = bodyCaptor.getValue();
        assertThat(body.get("chat_id")).isEqualTo("user-chat-789");
        assertThat(body.get("text")).contains("2024-06-15").contains("SOXL계좌");
    }

    @Test
    void notifyTradingReport_noUserBot_skips() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "kakao-1", "홍길동", User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, Instant.now(), Instant.now(), null);
        Account acc = account(userId, "노봇계좌");
        TradingSnapshot snapshot = new TradingSnapshot(10,
                new BigDecimal("20.00"), new BigDecimal("0.1733"), new BigDecimal("24.00"));
        TradingReport report = new TradingReport(
                LocalDate.of(2024, 6, 15), snapshot, List.of(), List.of(),
                new BigDecimal("66.00"), new BigDecimal("35.00"));

        adapter.notifyTradingReport(user, acc, report);

        verify(restTemplate, never()).postForObject(any(), any(), any());
    }
}
