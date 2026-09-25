package com.kista.notify.adapter.in.telegram;

import com.kista.notify.adapter.out.gateway.TelegramHttpClient;
import com.kista.notify.adapter.out.gateway.TelegramProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TelegramApiClientTest {

    @Mock
    RestClient restClient;

    @Mock
    TelegramHttpClient telegramHttpClient;

    // sendMessage 자체 URL/body 구성·빈 토큰 가드는 TelegramHttpClient가 소유 —
    // 여기서는 TelegramApiClient가 올바른 인자로 위임하는지만 검증 (TelegramAdapterTest가 TelegramHttpClient 동작 커버)
    @Test
    void sendMessage_delegates_to_telegramHttpClient() {
        TelegramProperties props = new TelegramProperties("test-token", "12345");
        TelegramApiClient sut = new TelegramApiClient(restClient, props, telegramHttpClient);

        sut.sendMessage("12345", "안녕");

        verify(telegramHttpClient).sendMessage("12345", "안녕", "test-token");
    }
}
