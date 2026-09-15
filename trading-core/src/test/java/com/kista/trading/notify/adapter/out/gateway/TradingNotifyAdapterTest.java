package com.kista.trading.notify.adapter.out.gateway;

import com.kista.sharedkernel.StrategyTicker;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TradingNotifyAdapterTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    TradingNotifyAdapter adapter;

    static final TelegramProperties PROPS = new TelegramProperties("admin-token", "admin-chat");
    static final TelegramProperties EMPTY_PROPS = new TelegramProperties("", "admin-chat");

    @BeforeEach
    void setUp() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        adapter = new TradingNotifyAdapter(httpClient, PROPS);
    }

    @Test
    void notifyMarketClosed_sendsCorrectUrl() {
        adapter.notifyMarketClosed();

        verify(restClient.post()).uri(contains("/botadmin-token/sendMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyInsufficientBalance_bodyContainsQuantityAndAmount() {
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        adapter.notifyInsufficientBalance(3, new BigDecimal("12.34"), StrategyTicker.SOXL);

        verify(restClient.post().uri(anyString())).body(bodyCaptor.capture());
        String text = ((Map<String, String>) bodyCaptor.getValue()).get("text");
        assertThat(text).contains("3주").contains("12.34").contains("SOXL");
    }

    @Test
    void notifyError_withEmptyToken_skipsRestClientCall() {
        TelegramHttpClient httpClient = new TelegramHttpClient(restClient);
        TradingNotifyAdapter noTokenAdapter = new TradingNotifyAdapter(httpClient, EMPTY_PROPS);

        noTokenAdapter.notifyError(new RuntimeException("실패"));

        verifyNoInteractions(restClient);
    }
}
