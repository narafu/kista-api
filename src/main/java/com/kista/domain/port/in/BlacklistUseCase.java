package com.kista.domain.port.in;

import java.util.UUID;

public interface BlacklistUseCase {
    boolean isBlacklisted(UUID userId);
    boolean isJtiBlacklisted(String jti); // 단일 AT jti 단위 블랙리스트 확인
}
