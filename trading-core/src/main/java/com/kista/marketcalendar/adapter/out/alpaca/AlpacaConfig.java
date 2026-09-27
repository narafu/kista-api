package com.kista.marketcalendar.adapter.out.alpaca;

import com.kista.platform.http.HttpClientTimeouts;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// stats 모듈 com.kista.stats.adapter.out.alpaca.AlpacaConfig와 클래스명이 같아 빈 이름 충돌 방지용으로 명시적 이름 지정
@Configuration("marketAlpacaConfig")
@EnableConfigurationProperties(AlpacaProperties.class)
public class AlpacaConfig {

    // stats 모듈 alpacaRestClient 빈과 이름 충돌 방지 — 소비자(AlpacaCalendarAdapter) 필드명도 동일하게 맞춤
    // baseUrl을 빈 생성 시점에 고정 — 호출부는 상대 경로만 넘기고 URL 결합은 이 한 곳으로 통일
    @Bean
    public RestClient marketAlpacaRestClient(AlpacaProperties alpacaProperties) {
        return RestClient.builder()
                .baseUrl(alpacaProperties.baseUrl())
                .requestFactory(alpacaRequestFactory())
                .build();
    }

    // package-private — AlpacaConfigTest에서 타임아웃 검증용으로 직접 호출
    static SimpleClientHttpRequestFactory alpacaRequestFactory() {
        // Alpaca 마켓 달력 조회 API 응답 지연 대비 타임아웃 설정 — 미설정 시 OS 기본값(~60초)로 무한 대기 가능
        return HttpClientTimeouts.timeouts(3_000, 7_000); // 연결 3초, 읽기 7초 (stats 모듈 AlpacaConfig와 동일 패턴)
    }
}
