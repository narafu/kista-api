package com.kista.application.service.auth;

import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.BlacklistPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class BlacklistService implements BlacklistUseCase {

    private final BlacklistPort blacklistPort;

    @Override
    public boolean isBlacklisted(UUID userId) {
        return blacklistPort.isBlacklisted(userId);
    }

    @Override
    public boolean isJtiBlacklisted(String jti) {
        return blacklistPort.isJtiBlacklisted(jti);
    }

    @Override
    public Instant roleChangedAt(UUID userId) {
        return blacklistPort.roleChangedAt(userId);
    }
}
