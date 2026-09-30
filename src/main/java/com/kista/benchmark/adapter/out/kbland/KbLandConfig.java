package com.kista.benchmark.adapter.out.kbland;

import com.kista.platform.http.HttpClientTimeouts;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration
@EnableConfigurationProperties(KbLandProperties.class)
@RequiredArgsConstructor
class KbLandConfig {

    private final KbLandProperties properties;

    @Bean
    RestClient kbLandRestClient() {
        return RestClient.builder()
                .uriBuilderFactory(kbLandUriBuilderFactory(properties.baseUrl()))
                .requestFactory(kbLandRequestFactory())
                .requestInterceptor(kbLandHeaderInterceptor())
                .build();
    }

    // package-private — KbLandHousingBenchmarkAdapterTest에서 동일 팩토리 재사용.
    // KB Land 쿼리 파라미터(한글 필드명)가 이미 퍼센트 인코딩된 리터럴 상수라 기본 인코딩 모드를 쓰면
    // '%'가 다시 인코딩(이중 인코딩)된다 — NONE으로 baseUrl+path를 그대로 이어붙이기만 한다.
    static DefaultUriBuilderFactory kbLandUriBuilderFactory(String baseUrl) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return factory;
    }

    // package-private — KbLandConfigTest에서 타임아웃 검증용으로 직접 호출
    static SimpleClientHttpRequestFactory kbLandRequestFactory() {
        // KB Land 아파트 벤치마크 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        // 읽기 20초: 주간 지수 API 응답 실측 3.5초 대비 여유 확보 — 5분위 조회와 빈 공유이므로 동일 적용
        return HttpClientTimeouts.timeouts(3_000, 20_000);
    }

    // package-private — KbLandHousingBenchmarkAdapterTest에서 동일 인터셉터 재사용
    static ClientHttpRequestInterceptor kbLandHeaderInterceptor() {
        // KB Land data-api는 프론트 내부 호출과 유사한 헤더가 없으면 400을 반환할 수 있다.
        return (request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.set(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
            headers.set(HttpHeaders.ACCEPT, "application/json, text/plain, */*");
            headers.set(HttpHeaders.REFERER, "https://data.kbland.kr/");
            headers.set("osType", "HUB");
            headers.set("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"");
            headers.set("sec-ch-ua-mobile", "?0");
            headers.set("sec-ch-ua-platform", "\"macOS\"");
            return execution.execute(request, body);
        };
    }
}
