package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FinanceAccountJpaRepository extends JpaRepository<FinanceAccountEntity, UUID> {

    // 폼 선택지·목록용, 활성 행만. 이중축: 내 개인 계좌(group_id IS NULL) ∪ 내 현재 그룹 계좌(group_id = :currentGroupId).
    // HQL의 "(:param IS NULL OR ...)" IS NULL 타입 추론 실패 패턴(다른 finance 리포지토리 참고)을 피하려 네이티브 쿼리 사용.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_accounts WHERE deleted_at IS NULL " +
            "AND ((user_id = :userId AND group_id IS NULL) OR group_id = :currentGroupId)")
    List<FinanceAccountEntity> findMyScope(@Param("userId") UUID userId, @Param("currentGroupId") UUID currentGroupId);

    Optional<FinanceAccountEntity> findByIdAndDeletedAtIsNull(UUID id);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.userId = :userId AND a.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
