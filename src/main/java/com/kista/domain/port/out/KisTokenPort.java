package com.kista.domain.port.out;

import java.util.UUID;

public interface KisTokenPort {
    String getToken(UUID accountId, String appKey, String appSecret);
    // 토큰 강제 만료 — 401 수신 시 호출하여 다음 getToken()에서 재발급 유도
    void invalidateToken(UUID accountId);
}
