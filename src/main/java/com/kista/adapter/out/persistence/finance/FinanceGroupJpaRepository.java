package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceGroupJpaRepository extends JpaRepository<FinanceGroupEntity, UUID> {

    Optional<FinanceGroupEntity> findByOwnerUserIdAndPersonalTrue(UUID ownerUserId);

    // finance_group_members는 관계 매핑이 아니라 raw UUID FK라 서브쿼리로 조인한다
    @Query("SELECT g FROM FinanceGroupEntity g WHERE g.id IN " +
            "(SELECT m.groupId FROM FinanceGroupMemberEntity m WHERE m.userId = :userId AND m.deletedAt IS NULL)")
    List<FinanceGroupEntity> findByMemberUserId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE FinanceGroupEntity g SET g.deletedAt = :now WHERE g.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceGroupEntity g SET g.personal = false WHERE g.id = :id")
    void unmarkPersonalById(@Param("id") UUID id);
}
