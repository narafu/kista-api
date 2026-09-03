package com.kista.notify.adapter.out.gateway;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TelegramAdapterTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    TelegramAdapter adapter;

    static final TelegramProperties PROPS =
            new TelegramProperties("test-token", "chat-123");
    static final TelegramProperties EMPTY_PROPS =
            new TelegramProperties("", "chat-123");

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        adapter = new TelegramAdapter(httpClient, PROPS);
    }

    // Account 10개 필드 생성자
    private Account account(UUID userId, String nickname) {
        return new Account(UUID.randomUUID(), userId, nickname,
                "74420614", "key", "secret", null,
                Account.Broker.KIS, null);
    }

    @Test
    void notifyMarketClosed_sendsCorrectUrl() {
        adapter.notifyMarketClosed();

        verify(restClient.post()).uri(contains("/bottest-token/sendMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyMarketClosed_bodyContainsChatId() {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.notifyMarketClosed();

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        assertThat((Map<String, String>) bodyCaptor.getValue()).containsEntry("chat_id", "chat-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_bodyContainsQuantityAndAmount() {
        AccountBalance balance = new AccountBalance(0, BigDecimal.ZERO,
                new BigDecimal("5.00")); // usdDeposit=5.00

        Account acc = account(UUID.randomUUID(), "테스트");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        adapter.notifyInsufficientBalance(acc, balance, Strategy.Ticker.SOXL);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        String text = ((Map<String, String>) bodyCaptor.getValue()).get("text");
        assertThat(text).contains("0주").contains("5.00");
    }

    @Test
    void send_withEmptyToken_skipsRestClientCall() {
        TelegramHttpClient emptyHttpClient = new TelegramHttpClient(restClient);
        TelegramAdapter noTokenAdapter = new TelegramAdapter(emptyHttpClient, EMPTY_PROPS);

        noTokenAdapter.notifyMarketClosed();

        verifyNoInteractions(restClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyError_bodyContainsExceptionMessage() {
        Exception ex = new RuntimeException("KIS API 호출 실패");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

        adapter.notifyError(ex);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        assertThat(((Map<String, String>) bodyCaptor.getValue()).get("text"))
                .contains("⚠️ 관리자 알림")
                .contains("KIS API 호출 실패");
    }
}
