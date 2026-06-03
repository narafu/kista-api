package com.kista.adapter.out.persistence.kistoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface KisTokenJpaRepository extends JpaRepository<KisTokenEntity, UUID> {

    @Query("SELECT e FROM KisTokenEntity e WHERE e.accountId = :accountId AND e.expiresAt > :now")
    Optional<KisTokenEntity> findValidToken(@Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);

    // 선제 갱신 스케줄러용 — threshold 이전에 만료될 계좌 ID 조회
    @Query("SELECT e.accountId FROM KisTokenEntity e WHERE e.expiresAt < :threshold")
    List<UUID> findAccountIdsByExpiresAtBefore(@Param("threshold") OffsetDateTime threshold);
}
