package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

interface KisTokenJpaRepository extends JpaRepository<KisTokenEntity, UUID> {

    @Query("SELECT e FROM KisTokenEntity e WHERE e.accountId = :accountId AND e.expiresAt > :now")
    Optional<KisTokenEntity> findValidToken(@Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);
}
