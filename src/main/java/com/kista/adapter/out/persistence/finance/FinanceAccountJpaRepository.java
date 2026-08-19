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

    // 폼 선택지·목록용, 활성 행만 (엔티티 클래스 레벨 @SQLRestriction 제거로 명시적 필터 필요 — FinanceAccountEntity 주석 참고)
    List<FinanceAccountEntity> findByGroupIdAndDeletedAtIsNull(UUID groupId);
    Optional<FinanceAccountEntity> findByIdAndDeletedAtIsNull(UUID id);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceAccountEntity a SET a.deletedAt = :now WHERE a.createdBy = :createdBy AND a.deletedAt IS NULL")
    void softDeleteByCreatedBy(@Param("createdBy") UUID createdBy, @Param("now") Instant now);

    // clearAutomatically=true 필수 — 벌크 UPDATE는 1차 캐시를 지우지 않아, 같은 트랜잭션에서 이 호출 전에
    // 이미 로드된 엔티티를 이후 findById로 다시 읽으면 groupId가 갱신 전 값으로 보인다(직접 재현 확인).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FinanceAccountEntity a SET a.groupId = :toGroupId " +
            "WHERE a.groupId = :fromGroupId AND a.createdBy = :createdBy")
    void reassignGroup(@Param("fromGroupId") UUID fromGroupId, @Param("toGroupId") UUID toGroupId, @Param("createdBy") UUID createdBy);
}
