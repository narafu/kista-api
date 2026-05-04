package com.kista.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

interface KisTokenJpaRepository extends JpaRepository<KisTokenEntity, Integer> {

    @Query("SELECT e FROM KisTokenEntity e WHERE e.expiresAt > :now")
    Optional<KisTokenEntity> findValidToken(@Param("now") OffsetDateTime now);
}
