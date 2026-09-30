package com.kista.platform.http;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientTimeoutsTest {

    @Test
    void timeouts가_연결_읽기_타임아웃을_설정한다() {
        SimpleClientHttpRequestFactory factory = HttpClientTimeouts.timeouts(3_000, 7_000);

        assertThat((Integer) ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3_000);
        assertThat((Integer) ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(7_000);
    }

    @Test
    void RestClients는_baseUrl_유무와_무관하게_클라이언트를_만든다() {
        assertTimeouts(RestClients.withTimeouts(3_000, 7_000));
        assertTimeouts(RestClients.withTimeouts("https://example.test", 3_000, 7_000));
    }

    // RestClient 내부 요청 팩토리가 SimpleClientHttpRequestFactory이고 연결 3s·읽기 7s인지 검증
    private static void assertTimeouts(RestClient client) {
        Object factory = ReflectionTestUtils.getField(client, "clientRequestFactory");
        assertThat(factory).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat((Integer) ReflectionTestUtils.getField(factory, "connectTimeout")).isEqualTo(3_000);
        assertThat((Integer) ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(7_000);
    }
}
