package com.kista.domain.port.out;

import java.util.UUID;

public interface KisTokenPort {
    String getToken(UUID accountId, String appKey, String appSecret);
    void testToken(UUID accountId, String appKey, String appSecret); // 키 검증 후 토큰을 캐시에 저장
}
