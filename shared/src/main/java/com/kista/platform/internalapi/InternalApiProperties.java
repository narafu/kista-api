package com.kista.platform.internalapi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "internal.api")
public record InternalApiProperties(
        String baseUrl,   // 내부 API 호출 대상 — root/trading-core 각자 반대 프로세스를 가리키도록 값이 다름(같은 프로퍼티 키를 두 프로세스가 각자 env로 채움)
        String token      // X-Internal-Token 값 — INTERNAL_API_TOKEN과 동일 소스
) {}
