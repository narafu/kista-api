package com.kista.adapter.in.telegram;

import com.kista.adapter.out.notify.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramApiClientTest {

    @Mock RestTemplate restTemplate;

    @Test
    void sendMessage_posts_to_telegram_api() {
        TelegramProperties props = new TelegramProperties("test-token", "12345");
        TelegramApiClient sut = new TelegramApiClient(restTemplate, props);

        sut.sendMessage("12345", "안녕");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(contains("/sendMessage"), captor.capture(), eq(String.class));
        assertThat(captor.getValue())
                .containsEntry("chat_id", "12345")
                .containsEntry("text", "안녕");
    }

    @Test
    void sendMessage_skips_when_token_blank() {
        TelegramProperties props = new TelegramProperties("", "12345");
        TelegramApiClient sut = new TelegramApiClient(restTemplate, props);

        sut.sendMessage("12345", "안녕");

        verifyNoInteractions(restTemplate);
    }
}
