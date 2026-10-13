package com.kista.shared.platform.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

// CNN 공포탐욕지수 API용 RestClient 팩토리 — root market·trading-core DCA가 같은 브라우저 헤더를 공유한다
public final class CnnRestClients {

    private CnnRestClients() {}

    // 브라우저 헤더 인터셉터를 단 RestClient (URL은 호출부가 절대 경로로 조립)
    public static RestClient withTimeouts(int connectMs, int readMs) {
        return RestClient.builder()
                .requestFactory(HttpClientTimeouts.timeouts(connectMs, readMs))
                .requestInterceptor(browserHeaderInterceptor())
                .build();
    }

    // public — 어댑터 테스트가 MockRestServiceServer 빌더에 같은 인터셉터를 붙여 헤더를 검증한다
    public static ClientHttpRequestInterceptor browserHeaderInterceptor() {
        // CNN Cloudflare 봇 감지 우회 — User-Agent 외 Accept·Referer 등 브라우저 헤더 모방 필수
        return (request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9");
            // 압축 인코딩을 강제하면 일부 응답이 자동 해제되지 않아 JSON 파싱이 깨질 수 있다.
            headers.set(HttpHeaders.REFERER, "https://edition.cnn.com/markets/fear-and-greed");
            headers.set("Origin", "https://edition.cnn.com");
            return execution.execute(request, body);
        };
    }
}
