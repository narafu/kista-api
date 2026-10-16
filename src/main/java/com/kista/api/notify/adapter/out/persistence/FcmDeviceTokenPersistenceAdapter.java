package com.kista.api.notify.adapter.out.persistence;

import com.kista.api.notify.application.port.output.FcmDeviceTokenPort;
import com.kista.shared.platform.persistence.UuidV7;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
        repository.upsert(UuidV7.next(), userId, token, normalizePlatform(platform));
    }

    @Override
    // REQUIRES_NEW — 가입 승인·거절 알림(AFTER_COMMIT 리스너) 발송 중 만료 토큰을 지우므로 커밋된 트랜잭션에 합류하면 삭제가 유실된다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(UUID userId, String token) {
        // 동일 무효 토큰을 여러 전략 리포트 스레드가 동시에 삭제 시도할 수 있음 — 이미 삭제됐으면 목적 달성으로 간주
        try {
            repository.deleteByUserIdAndToken(userId, token);
        } catch (ObjectOptimisticLockingFailureException _) {
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
