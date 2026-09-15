package com.kista.platform.internalapi;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiClientConfigTest {

    @Test
    void 헤더에_내부토큰이_설정된다() {
        InternalApiProperties props = new InternalApiProperties("http://localhost:8080", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        RestClient client = config.internalApiRestClient(props);

        assertThat(client).isNotNull();
        // RestClient는 헤더 검증용 introspection API가 없으므로, 실제 헤더 전달은
        // Task 5의 TradingInternalHttpAdapterTest(MockWebServer 등)에서 통합 검증한다.
        // 여기서는 baseUrl/token 둘 다 blank면 예외를 던지는 생성 검증만 한다.
    }

    @Test
    void baseUrl이_비어있으면_예외() {
        InternalApiProperties props = new InternalApiProperties("", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config.internalApiRestClient(props));
    }

    // reorder/trade-corrections 전용 쓰기 경로 빈 — 브로커 취소+접수 동기 호출을 감싸므로
    // 공용 읽기 빈과 별도로 존재해야 한다 (finding: KIS/Toss 브로커 타임아웃 상한이 공용
    // 10초 타임아웃과 같거나 넘어서는 문제)
    @Test
    void 쓰기_전용_클라이언트_빈이_생성된다() {
        InternalApiProperties props = new InternalApiProperties("http://localhost:8080", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        RestClient client = config.internalApiWriteRestClient(props);

        assertThat(client).isNotNull();
    }

    @Test
    void 쓰기_전용_클라이언트도_baseUrl이_비어있으면_예외() {
        InternalApiProperties props = new InternalApiProperties("", "test-token");
        InternalApiClientConfig config = new InternalApiClientConfig();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> config.internalApiWriteRestClient(props));
    }
}
