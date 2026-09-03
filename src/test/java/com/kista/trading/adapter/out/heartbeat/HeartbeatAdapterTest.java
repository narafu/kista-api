package com.kista.trading.adapter.out.heartbeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatAdapterTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient heartbeatRestClient;

    @Test
    void pingOpen_urlSet_sendsGet() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestClient,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", "https://hc-ping.com/close-uuid"));
        adapter.pingOpen();

        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(heartbeatRestClient.get()).uri(urlCaptor.capture());
        assertThat(urlCaptor.getValue()).isEqualTo("https://hc-ping.com/open-uuid");
    }

    @Test
    void pingClose_urlBlank_skipsWithoutCall() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestClient, new HeartbeatProperties("", ""));
        adapter.pingClose();
        verifyNoInteractions(heartbeatRestClient);
    }

    @Test
    void ping_httpFailure_swallowedNotThrown() {
        // 핑 실패가 매매 흐름을 깨면 안 됨 — 로그만 남기고 삼킴
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestClient,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", null));
        when(heartbeatRestClient.get().uri(anyString()).retrieve().body(String.class))
                .thenThrow(new RestClientException("timeout"));
        assertThatCode(adapter::pingOpen).doesNotThrowAnyException();
    }
}
