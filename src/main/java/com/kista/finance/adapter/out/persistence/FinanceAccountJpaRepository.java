package com.kista.finance.adapter.out.persistence;

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

    // 전역 계좌번호 중복 체크(HMAC-SHA256 해시). excludeId 없는 버전은 신규 등록용, 있는 버전은 update 시 자기 자신 제외용.
    boolean existsByAccountNoHashAndDeletedAtIsNull(String accountNoHash);
    boolean existsByAccountNoHashAndDeletedAtIsNullAndIdNot(String accountNoHash, UUID excludeId);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.userId = :userId AND a.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
