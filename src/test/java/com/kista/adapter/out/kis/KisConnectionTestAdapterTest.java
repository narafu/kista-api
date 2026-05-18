package com.kista.adapter.out.kis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisConnectionTestAdapter 단위 테스트")
class KisConnectionTestAdapterTest {

    @Mock RestTemplate kisRestTemplate;

    KisConnectionTestAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://openapi.koreainvestment.com:9443", "key", "secret"
    );

    @BeforeEach
    void setUp() {
        adapter = new KisConnectionTestAdapter(kisRestTemplate, TEST_PROPS);
    }

    @Test
    @DisplayName("KIS OAuth 2xx 응답 시 true 반환")
    void test_whenKisReturns2xx_returnsTrue() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"access_token\":\"tok\"}"));

        assertThat(adapter.test("appKey", "appSecret")).isTrue();
    }

    @Test
    @DisplayName("KIS OAuth 4xx 응답 시 false 반환")
    void test_whenKisReturns4xx_returnsFalse() {
        when(kisRestTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized",
                        HttpHeaders.EMPTY, new byte[]{}, null));

        assertThat(adapter.test("badKey", "badSecret")).isFalse();
    }
}
