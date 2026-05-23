package com.kista.adapter.out.persistence.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AccountJpaRepository extends JpaRepository<AccountEntity, UUID> {

    List<AccountEntity> findByUserId(UUID userId);

    int countByUserId(UUID userId);

    @Modifying
    @Query("UPDATE AccountEntity a SET a.deletedAt = :now WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE AccountEntity a SET a.deletedAt = :now WHERE a.userId = :userId AND a.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
