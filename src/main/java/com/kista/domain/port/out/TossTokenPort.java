package com.kista.domain.port.out;

import java.util.UUID;

public interface TossTokenPort {
    // clientId: Toss client_id, clientSecret: Toss client_secret
    String getToken(UUID accountId, String clientId, String clientSecret);
    // 토큰 강제 만료 — 401 수신 시 호출하여 다음 getToken()에서 재발급 유도
    void invalidateToken(UUID accountId);
}
