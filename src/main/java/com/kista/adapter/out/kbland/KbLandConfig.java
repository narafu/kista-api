package com.kista.adapter.out.kbland;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KbLandProperties.class)
class KbLandConfig {

    @Bean
    RestClient kbLandRestClient() {
        return RestClient.builder()
                .requestFactory(kbLandRequestFactory())
                .requestInterceptor(kbLandHeaderInterceptor())
                .build();
    }

    // package-private — KbLandConfigTest에서 타임아웃 검증용으로 직접 호출
    static SimpleClientHttpRequestFactory kbLandRequestFactory() {
        // KB Land 아파트 벤치마크 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000); // 연결 타임아웃 3초
        factory.setReadTimeout(20_000);   // 읽기 타임아웃 20초 (주간 지수 API 응답 실측 3.5초 대비 여유 확보 — 5분위 조회와 빈 공유이므로 동일 적용)
        return factory;
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
