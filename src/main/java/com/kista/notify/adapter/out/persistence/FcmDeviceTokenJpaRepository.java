package com.kista.notify.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface FcmDeviceTokenJpaRepository extends JpaRepository<FcmDeviceTokenEntity, UUID> {
    List<FcmDeviceTokenEntity> findAllByUserId(UUID userId);
    void deleteByUserIdAndToken(UUID userId, String token);

    // token 유니크 제약 위 네이티브 upsert — 동시 요청 race를 애플리케이션 레벨 조회-후-저장 없이 원천 차단
    @Modifying
    @Query(value = """
            INSERT INTO fcm_device_tokens (user_id, token, platform)
            VALUES (:userId, :token, :platform)
            ON CONFLICT (token)
            DO UPDATE SET user_id = excluded.user_id, platform = excluded.platform
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId, @Param("token") String token, @Param("platform") String platform);
}
