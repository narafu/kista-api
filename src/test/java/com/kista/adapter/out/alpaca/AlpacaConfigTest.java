package com.kista.adapter.out.alpaca;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AlpacaConfigTest {

    @Test
    void restTemplate가_타임아웃을_설정한_factory를_사용한다() {
        // Config 클래스 직접 인스턴스화 후 빈 메서드 호출
        AlpacaConfig config = new AlpacaConfig();
        RestTemplate alpacaRestTemplate = config.alpacaRestTemplate();

        // Alpaca는 인터셉터가 없으므로 getRequestFactory()가 직접 SimpleClientHttpRequestFactory를 반환
        assertThat(alpacaRestTemplate.getRequestFactory())
                .isInstanceOf(SimpleClientHttpRequestFactory.class);

        SimpleClientHttpRequestFactory factory =
                (SimpleClientHttpRequestFactory) alpacaRestTemplate.getRequestFactory();

        // 타임아웃 검증
        Integer connectTimeout = (Integer) ReflectionTestUtils.getField(factory, "connectTimeout");
        Integer readTimeout = (Integer) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(connectTimeout).isEqualTo(3_000);
        assertThat(readTimeout).isEqualTo(7_000);
    }
}
