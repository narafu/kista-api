package com.kista.adapter.out.persistence.fcm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FcmDeviceTokenJpaRepository extends JpaRepository<FcmDeviceTokenEntity, UUID> {
    List<FcmDeviceTokenEntity> findAllByUserId(UUID userId);
    Optional<FcmDeviceTokenEntity> findByUserIdAndToken(UUID userId, String token);
    void deleteByUserIdAndToken(UUID userId, String token);
    void deleteByUserIdAndPlatform(UUID userId, String platform); // 같은 플랫폼 구형 토큰 일괄 삭제
}
