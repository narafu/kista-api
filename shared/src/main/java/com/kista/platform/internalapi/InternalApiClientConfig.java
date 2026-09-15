package com.kista.platform.internalapi;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiClientConfig {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    // 커넥션/응답 타임아웃 — 미설정 시 hung 호출이 admin 요청 스레드를 무한 점유
    private static final Timeout CONNECT_TIMEOUT = Timeout.ofSeconds(3);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(10);

    // 쓰기 경로(reorder/trade-corrections) 전용 응답 타임아웃 — 이 두 엔드포인트는 내부에서
    // 브로커(KIS/Toss) 취소+접수를 동기 호출한다. 브로커 자체 타임아웃 상한(KisConfig: 3s+7s=10s,
    // TossConfig: 3s+10s=13s)이 이미 공용 RESPONSE_TIMEOUT(10s)과 같거나 넘어서, 내부 HTTP 호출이
    // 브로커 호출이 끝나기 전에 먼저 타임아웃되면 trading 쪽 @Transactional은 커밋되는데
    // admin 쪽은 예외로 떨어져 감사 로그(auditLogPort.log)가 기록되지 않는 사고가 난다.
    // 20s는 브로커 호출 1회 기준(Toss 상한 13s에 여유) — ReorderService.reorder()는 원본 주문이
    // PLACED 상태이고 timing=IMMEDIATE일 때 취소+접수 두 브로커 호출을 순차로 하므로 그 경로의
    // 실제 상한은 Toss 26s/KIS 20s까지 올라간다(현재 20s는 이 2회 경로 기준으로는 여전히 타이트).
    // 끊긴 커넥션으로 응답을 영영 못 받는 잔여 위험도 타임아웃 값 조정으로는 없앨 수 없다.
    private static final Timeout WRITE_RESPONSE_TIMEOUT = Timeout.ofSeconds(20);

    @Bean
    public RestClient internalApiRestClient(InternalApiProperties props) {
        return buildClient(props, requestFactory(RESPONSE_TIMEOUT));
    }

    // reorder/trade-corrections 전용 — 브로커 호출을 동기로 감싸는 쓰기 엔드포인트만 이 빈을 주입받는다
    @Bean
    public RestClient internalApiWriteRestClient(InternalApiProperties props) {
        return buildClient(props, requestFactory(WRITE_RESPONSE_TIMEOUT));
    }

    private RestClient buildClient(InternalApiProperties props, HttpComponentsClientHttpRequestFactory requestFactory) {
        if (props.baseUrl() == null || props.baseUrl().isBlank()) {
            throw new IllegalArgumentException("internal.api.base-url이 설정되지 않았습니다");
        }
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(INTERNAL_TOKEN_HEADER, props.token())
                .requestFactory(requestFactory)
                .build();
    }

    // httpclient5 기본 HttpClient는 DefaultHttpRequestRetryStrategy로 POST를 포함해 429 등에
    // 자동 재시도한다 — reorder/trade-correction처럼 실제 브로커 주문을 유발하는 쓰기 요청에는
    // idempotency 보장이 전혀 없어 위험하다(중복 취소·중복 주문·cycle_position 중복 반영). 여기서
    // 전면 비활성화 — 읽기 전용 어댑터들도 재시도를 원하지 않으므로 공용 빈에서 끄는 게 안전하다.
    private HttpComponentsClientHttpRequestFactory requestFactory(Timeout responseTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setResponseTimeout(responseTimeout)
                .build();
        var httpClient = HttpClients.custom()
                .disableAutomaticRetries()
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
