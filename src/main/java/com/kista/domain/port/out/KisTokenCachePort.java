package com.kista.domain.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface KisTokenCachePort {
    Optional<String> findValidToken(OffsetDateTime now); // now 이후에 만료되는 토큰 조회
    void saveToken(String accessToken, OffsetDateTime expiresAt); // 토큰과 만료시각 저장
}
