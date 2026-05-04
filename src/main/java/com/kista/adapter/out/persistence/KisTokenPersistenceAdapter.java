package com.kista.adapter.out.persistence;

import com.kista.domain.port.out.KisTokenCachePort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // KisTokenJpaRepository가 package-private
public class KisTokenPersistenceAdapter implements KisTokenCachePort {

    private static final int SINGLETON_ROW_ID = 1; // 단일 행 upsert용 고정 PK

    private final KisTokenJpaRepository repository;

    @Override
    public Optional<String> findValidToken(OffsetDateTime now) {
        // 현재 시각 이후 만료 토큰이 있으면 access_token만 추출
        return repository.findValidToken(now).map(KisTokenEntity::getAccessToken);
    }

    @Override
    public void saveToken(String accessToken, OffsetDateTime expiresAt) {
        // id=1 고정 → Spring Data merge()로 INSERT or UPDATE (upsert)
        repository.save(new KisTokenEntity(SINGLETON_ROW_ID, accessToken, expiresAt));
    }
}
