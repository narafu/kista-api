package com.kista.adapter.out.kbland;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableConfigurationProperties(KbLandProperties.class)
class KbLandConfig {

    @Bean
    RestTemplate kbLandRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        // KB Land data-api는 프론트 내부 호출과 유사한 헤더가 없으면 400을 반환할 수 있다.
        restTemplate.getInterceptors().add((request, body, execution) -> {
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
        });
        return restTemplate;
    }
}
