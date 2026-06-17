package com.kista.domain.port.out;

import com.kista.domain.model.auth.RefreshToken;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenPort {
    void save(RefreshToken token);
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteAllByUserId(UUID userId); // 탈퇴/거절 시 전체 세션 종료
}
