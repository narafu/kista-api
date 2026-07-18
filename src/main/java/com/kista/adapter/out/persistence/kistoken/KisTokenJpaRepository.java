package com.kista.adapter.out.persistence.kistoken;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

interface KisTokenJpaRepository extends JpaRepository<KisTokenEntity, UUID> {

    @Query("SELECT e FROM KisTokenEntity e WHERE e.accountId = :accountId AND e.expiresAt > :now")
    Optional<KisTokenEntity> findValidToken(@Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE KisTokenEntity e SET e.accessToken = :invalidatedToken, e.expiresAt = :invalidatedAt " +
            "WHERE e.accountId = :accountId AND e.accessToken = :rejectedAccessToken")
    int invalidateToken(@Param("accountId") UUID accountId,
                        @Param("rejectedAccessToken") String rejectedAccessToken,
                        @Param("invalidatedToken") String invalidatedToken,
                        @Param("invalidatedAt") OffsetDateTime invalidatedAt);
}
