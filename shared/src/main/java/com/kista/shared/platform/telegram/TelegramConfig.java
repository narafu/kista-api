package com.kista.shared.platform.telegram;

import com.kista.shared.platform.http.RestClients;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

    // 파라미터명을 빈 이름(telegramRestClient)과 일치시켜 다중 RestClient 빈 중 정확히 이 빈을 주입.
    // local 프로파일이면 모든 메시지에 [LOCAL] 접두사 — 운영과 같은 관리자 봇을 공유해 출처 구분이 필요
    @Bean
    public TelegramHttpClient telegramHttpClient(RestClient telegramRestClient, Environment environment) {
        String prefix = environment.matchesProfiles("local") ? TelegramHttpClient.LOCAL_PREFIX : "";
        return new TelegramHttpClient(telegramRestClient, prefix);
    }
}
