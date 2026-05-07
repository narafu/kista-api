package com.kista.adapter.out.persistence;

import com.kista.domain.port.out.KisTokenCachePort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // KisTokenJpaRepository가 package-private
public class KisTokenPersistenceAdapter implements KisTokenCachePort {

    private final KisTokenJpaRepository repository;

    @Override
    public Optional<String> findValidToken(UUID accountId, OffsetDateTime now) {
        // account_id + 만료 시각 기준으로 유효 토큰 조회
        return repository.findValidToken(accountId, now).map(KisTokenEntity::getAccessToken);
    }

    @Override
    public void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
        // accountId를 PK로 upsert (Spring Data: 기존 행이면 UPDATE, 없으면 INSERT)
        repository.save(new KisTokenEntity(accountId, accessToken, expiresAt));
    }
}
