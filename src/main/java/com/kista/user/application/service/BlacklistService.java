package com.kista.user.application.service;

import com.kista.user.application.usecase.BlacklistUseCase;
import com.kista.user.application.port.output.BlacklistPort;
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
