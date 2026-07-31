package com.kista.adapter.out.feargreed;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FearGreedConfigTest {

    @Test
    void restTemplate가_인터셉터와_타임아웃을_모두_설정한다() {
        // Config 클래스 직접 인스턴스화 후 빈 메서드 호출
        FearGreedConfig config = new FearGreedConfig();
        RestTemplate fearGreedRestTemplate = config.fearGreedRestTemplate();

        ClientHttpRequestFactory factory = fearGreedRestTemplate.getRequestFactory();

        // FearGreed는 인터셉터가 있으므로 래퍼된 InterceptingClientHttpRequestFactory
        assertThat(factory).isInstanceOf(InterceptingClientHttpRequestFactory.class);

        // 인터셉터 확인
        assertThat(fearGreedRestTemplate.getInterceptors()).isNotEmpty();

        // 언랩해서 실제 SimpleClientHttpRequestFactory의 타임아웃 검증
        InterceptingClientHttpRequestFactory interceptingFactory =
                (InterceptingClientHttpRequestFactory) factory;
        SimpleClientHttpRequestFactory delegateFactory =
                (SimpleClientHttpRequestFactory) ReflectionTestUtils.getField(
                        interceptingFactory, "requestFactory");

        Integer connectTimeout = (Integer) ReflectionTestUtils.getField(delegateFactory, "connectTimeout");
        Integer readTimeout = (Integer) ReflectionTestUtils.getField(delegateFactory, "readTimeout");

        assertThat(connectTimeout).isEqualTo(3_000);
        assertThat(readTimeout).isEqualTo(7_000);
    }
}
