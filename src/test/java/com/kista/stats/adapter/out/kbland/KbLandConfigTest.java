package com.kista.stats.adapter.out.kbland;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KbLandConfigTest {

    @Test
    void requestFactory가_타임아웃을_설정한다() {
        SimpleClientHttpRequestFactory factory = KbLandConfig.kbLandRequestFactory();

        Integer connectTimeout = (Integer) ReflectionTestUtils.getField(factory, "connectTimeout");
        Integer readTimeout = (Integer) ReflectionTestUtils.getField(factory, "readTimeout");

        assertThat(connectTimeout).isEqualTo(3_000);
        assertThat(readTimeout).isEqualTo(20_000);
    }
}
