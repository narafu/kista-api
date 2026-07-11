package com.kista.adapter.out.heartbeat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatAdapterTest {

    @Mock RestTemplate heartbeatRestTemplate;

    @Test
    void pingOpen_urlSet_sendsGet() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", "https://hc-ping.com/close-uuid"));
        adapter.pingOpen();
        verify(heartbeatRestTemplate).getForObject("https://hc-ping.com/open-uuid", String.class);
    }

    @Test
    void pingClose_urlBlank_skipsWithoutCall() {
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate, new HeartbeatProperties("", ""));
        adapter.pingClose();
        verifyNoInteractions(heartbeatRestTemplate);
    }

    @Test
    void ping_httpFailure_swallowedNotThrown() {
        // 핑 실패가 매매 흐름을 깨면 안 됨 — 로그만 남기고 삼킴
        HeartbeatAdapter adapter = new HeartbeatAdapter(heartbeatRestTemplate,
                new HeartbeatProperties("https://hc-ping.com/open-uuid", null));
        when(heartbeatRestTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("timeout"));
        assertThatCode(adapter::pingOpen).doesNotThrowAnyException();
    }
}
