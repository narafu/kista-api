package com.kista.domain.port.in;

public interface KisConnectionTestUseCase {
    // KIS OAuth 토큰 발급 시도로 자격증명 유효성 검증
    boolean test(String appKey, String appSecret);
}
