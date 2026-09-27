package com.kista.platform.internalapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

// Jackson3(tools.jackson) 전환 검증 — detail 필드 정상/누락/타입불일치 3케이스
class InternalApiErrorDetailsTest {

    @Test
    void detail_필드가_문자열이면_그대로_반환() {
        ClientHttpResponse response = jsonResponse("{\"detail\":\"계좌를 찾을 수 없습니다\"}");

        String result = InternalApiErrorDetails.detailOrDefault(response, "fallback");

        assertThat(result).isEqualTo("계좌를 찾을 수 없습니다");
    }

    @Test
    void 본문이_JSON이_아니면_fallback() {
        ClientHttpResponse response = jsonResponse("not-a-json-body");

        String result = InternalApiErrorDetails.detailOrDefault(response, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void detail_필드가_문자열이_아니면_fallback() {
        ClientHttpResponse response = jsonResponse("{\"detail\":123}");

        String result = InternalApiErrorDetails.detailOrDefault(response, "fallback");

        assertThat(result).isEqualTo("fallback");
    }

    private static ClientHttpResponse jsonResponse(String body) {
        return new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), 500);
    }
}
