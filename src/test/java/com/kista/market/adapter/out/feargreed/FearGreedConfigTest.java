package com.kista.market.adapter.out.feargreed;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class FearGreedConfigTest {

    @Test
    void requestFactory가_타임아웃을_설정한다() {
        SimpleClientHttpRequestFactory factory = FearGreedConfig.fearGreedRequestFactory();

        Integer connectTimeout = (Integer) ReflectionTestUtils.getField(factory, "connectTimeout");
        Integer readTimeout = (Integer) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(connectTimeout).isEqualTo(3_000);
        assertThat(readTimeout).isEqualTo(7_000);
    }
}
