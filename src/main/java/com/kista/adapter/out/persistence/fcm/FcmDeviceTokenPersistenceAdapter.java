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
        // 같은 플랫폼 기존 토큰 삭제 후 신규 등록 (재로그인 시 구형 토큰 누적 방지)
        if (repository.findByUserIdAndToken(userId, token).isEmpty()) {
            repository.deleteByUserIdAndPlatform(userId, platform);
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
