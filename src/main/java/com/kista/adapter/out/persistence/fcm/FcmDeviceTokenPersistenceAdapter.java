package com.kista.adapter.out.persistence.fcm;

import com.kista.domain.port.out.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FcmDeviceTokenPersistenceAdapter implements FcmDeviceTokenPort {

    private final FcmDeviceTokenJpaRepository repository;

    @Override
    @Transactional
    public void save(UUID userId, String token, String platform) {
        // 중복 토큰은 무시 (UNIQUE 제약)
        if (repository.findByUserIdAndToken(userId, token).isEmpty()) {
            repository.save(FcmDeviceTokenEntity.of(userId, token, platform));
        }
    }

    @Override
    @Transactional
    public void delete(UUID userId, String token) {
        repository.deleteByUserIdAndToken(userId, token);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findTokensByUserId(UUID userId) {
        return repository.findAllByUserId(userId).stream()
                .map(FcmDeviceTokenEntity::getToken)
                .toList();
    }
}
