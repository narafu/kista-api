package com.kista.platform.telegram;

import com.kista.platform.http.RestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

// 두 프로세스(root/trading-core)가 공유하는 텔레그램 인프라 배선 — 빈 이름은 프로세스 무관하게 telegramRestClient/telegramHttpClient.
// RestClient 빈이 여럿이라 주입 지점은 필드명 telegramRestClient(또는 TelegramHttpClient 타입)로만 받는다
@Configuration
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramConfig {

    @Bean
    public RestClient telegramRestClient() {
        // 텔레그램 API 응답 지연 대비 타임아웃 — 미설정 시 OS 기본값으로 무한 대기 가능
        return RestClients.withTimeouts(3_000, 7_000); // 연결 3초, 읽기 7초
    }

    // 파라미터명을 빈 이름(telegramRestClient)과 일치시켜 다중 RestClient 빈 중 정확히 이 빈을 주입
    @Bean
    public TelegramHttpClient telegramHttpClient(RestClient telegramRestClient) {
        return new TelegramHttpClient(telegramRestClient);
    }
}
