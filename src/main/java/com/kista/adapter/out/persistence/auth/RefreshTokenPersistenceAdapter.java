package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import com.kista.domain.port.out.RefreshTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
class RefreshTokenPersistenceAdapter implements RefreshTokenPort {

    private final RefreshTokenJpaRepository repository;

    @Override
    @Transactional
    public void save(RefreshToken token) {
        repository.save(RefreshTokenEntity.from(token));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(RefreshTokenEntity::toDomain);
    }

    @Override
    @Transactional
    public void deleteByTokenHash(String tokenHash) {
        repository.deleteByTokenHash(tokenHash);
    }

    @Override
    @Transactional
    public void deleteAllByUserId(UUID userId) {
        repository.deleteAllByUserId(userId);
    }

    @Override
    @Transactional
    public int deleteAllExpired() {
        return repository.deleteAllByExpiresAtBefore(Instant.now());
    }
}
