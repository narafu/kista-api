package com.kista.finance.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceGroupMemberJpaRepository extends JpaRepository<FinanceGroupMemberEntity, UUID> {

    List<FinanceGroupMemberEntity> findByGroupId(UUID groupId);

    Optional<FinanceGroupMemberEntity> findByGroupIdAndUserId(UUID groupId, UUID userId);

    // ON CONFLICT DO NOTHING으로 멱등하게 처리한다 — 존재 확인 후 catch(DataIntegrityViolationException)로
    // 삼키는 방식은 PostgreSQL에서 제약 위반이 발생한 순간 그 트랜잭션 전체가 abort 상태로 넘어가버려서
    // Java 레벨에서 예외를 잡아도 소용없다(다음 문장이 "current transaction is aborted"로 실패한다).
    // uq_finance_group_members_group_user와 동일한 partial index 조건을 ON CONFLICT의 WHERE에 그대로 반복해야
    // PostgreSQL이 충돌 대상을 그 인덱스로 추론한다.
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO finance_group_members (id, group_id, user_id, role, joined_at)
            VALUES (gen_random_uuid(), :groupId, :userId, :role, now())
            ON CONFLICT (group_id, user_id) WHERE deleted_at IS NULL DO NOTHING
            """)
    void insertIfAbsent(@Param("groupId") UUID groupId, @Param("userId") UUID userId, @Param("role") String role);

    @Modifying
    @Query("UPDATE FinanceGroupMemberEntity m SET m.deletedAt = :now WHERE m.groupId = :groupId AND m.userId = :userId")
    void softDeleteByGroupIdAndUserId(@Param("groupId") UUID groupId, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceGroupMemberEntity m SET m.role = :role WHERE m.groupId = :groupId AND m.userId = :userId")
    void updateRole(@Param("groupId") UUID groupId, @Param("userId") UUID userId,
            @Param("role") com.kista.finance.domain.model.FinanceGroup.MemberRole role);
}
