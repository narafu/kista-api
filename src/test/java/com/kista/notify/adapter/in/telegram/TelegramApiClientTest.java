package com.kista.notify.adapter.in.telegram;

import com.kista.notify.adapter.out.gateway.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TelegramApiClientTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient restClient;

    @Test
    void sendMessage_posts_to_telegram_api() {
        TelegramProperties props = new TelegramProperties("test-token", "12345");
        TelegramApiClient sut = new TelegramApiClient(restClient, props);

        sut.sendMessage("12345", "안녕");

        verify(restClient.post()).uri(contains("/sendMessage"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restClient.post().uri(anyString())).body(captor.capture());
        assertThat(captor.getValue())
                .containsEntry("chat_id", "12345")
                .containsEntry("text", "안녕");
    }

    @Test
    void sendMessage_skips_when_token_blank() {
        TelegramProperties props = new TelegramProperties("", "12345");
        TelegramApiClient sut = new TelegramApiClient(restClient, props);

        sut.sendMessage("12345", "안녕");

        verifyNoInteractions(restClient);
    }
}
