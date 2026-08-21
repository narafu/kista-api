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

    // finance_group_members는 관계 매핑이 아니라 raw UUID FK라 서브쿼리로 조인한다
    @Query("SELECT g FROM FinanceGroupEntity g WHERE g.id IN " +
            "(SELECT m.groupId FROM FinanceGroupMemberEntity m WHERE m.userId = :userId AND m.deletedAt IS NULL)")
    List<FinanceGroupEntity> findByMemberUserId(@Param("userId") UUID userId);

    // 1인1그룹 불변식이므로 결과는 최대 1개. 무그룹 유저는 empty.
    @Query(nativeQuery = true, value = """
            SELECT m.group_id FROM finance_group_members m
            JOIN finance_groups g ON g.id = m.group_id
            WHERE m.user_id = :userId AND m.deleted_at IS NULL AND g.deleted_at IS NULL
            LIMIT 1
            """)
    Optional<UUID> findCurrentGroupId(@Param("userId") UUID userId);

    @Modifying
    @Query("UPDATE FinanceGroupEntity g SET g.deletedAt = :now WHERE g.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);
}
