package com.kista.notify.adapter.out.persistence;

import com.kista.notify.application.port.output.FcmDeviceTokenPort;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
        repository.upsert(userId, token, normalizePlatform(platform));
    }

    @Override
    @Transactional
    public void delete(UUID userId, String token) {
        // 동일 무효 토큰을 여러 전략 리포트 스레드가 동시에 삭제 시도할 수 있음 — 이미 삭제됐으면 목적 달성으로 간주
        try {
            repository.deleteByUserIdAndToken(userId, token);
        } catch (ObjectOptimisticLockingFailureException ignored) {
        }
    }

    @Override
    @Transactional
    public void deleteAllByUserId(UUID userId) {
        repository.deleteAllByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> findTokensByUserId(UUID userId) {
        return repository.findAllByUserId(userId).stream()
                .map(FcmDeviceTokenEntity::getToken)
                .distinct()
                .toList();
    }

    private static String normalizePlatform(String platform) {
        String normalized = platform == null ? "" : platform.strip().toUpperCase();
        if (!List.of("WEB", "ANDROID", "IOS").contains(normalized)) {
            throw new IllegalArgumentException("알 수 없는 FCM 플랫폼: " + platform + ". 허용값: WEB, ANDROID, IOS");
        }
        return normalized;
    }
}
