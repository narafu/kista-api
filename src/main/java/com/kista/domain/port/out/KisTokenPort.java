package com.kista.domain.port.out;

import java.util.UUID;

public interface KisTokenPort {
    String getToken(UUID accountId, String appKey, String appSecret);
    void testToken(String appKey, String appSecret); // 캐시 없는 1회성 유효성 검증용
}
