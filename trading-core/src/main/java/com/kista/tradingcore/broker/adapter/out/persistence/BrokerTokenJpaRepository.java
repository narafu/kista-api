package com.kista.tradingcore.broker.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

interface BrokerTokenJpaRepository extends JpaRepository<BrokerTokenEntity, UUID> {

    @Query("SELECT e FROM BrokerTokenEntity e WHERE e.accountId = :accountId AND e.expiresAt > :now")
    Optional<BrokerTokenEntity> findValidToken(@Param("accountId") UUID accountId, @Param("now") OffsetDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE BrokerTokenEntity e SET e.accessToken = :invalidatedToken, e.expiresAt = :invalidatedAt " +
            "WHERE e.accountId = :accountId AND e.accessToken = :rejectedAccessToken")
    int invalidateToken(@Param("accountId") UUID accountId,
                        @Param("rejectedAccessToken") String rejectedAccessToken,
                        @Param("invalidatedToken") String invalidatedToken,
                        @Param("invalidatedAt") OffsetDateTime invalidatedAt);
}
