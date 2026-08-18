package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface FinanceAssetSnapshotJpaRepository extends JpaRepository<FinanceAssetSnapshotEntity, UUID> {

    // from/to/createdBy는 선택적 필터 — null이면 무시. entryDate 최신순.
    // HQL의 "(:from IS NULL OR ...)" 형태는 PostgreSQL이 IS NULL 자리 바인드 파라미터의 타입을 추론하지 못해
    // "could not determine data type of parameter"로 실패하고, HQL CAST(:from AS date)로 우회를 시도하면
    // 이번엔 Hibernate가 그 파라미터를 bytea로 바인딩해 "cannot cast type bytea to date"로 실패한다.
    // 네이티브 쿼리 + PostgreSQL ::date 캐스트로 우회한다 — JDBC가 LocalDate를 곧바로 바인딩하므로 문제없다.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_asset_snapshots WHERE group_id = :groupId " +
            "AND deleted_at IS NULL " +
            "AND (CAST(:from AS date) IS NULL OR entry_date >= CAST(:from AS date)) " +
            "AND (CAST(:to AS date) IS NULL OR entry_date <= CAST(:to AS date)) " +
            "AND (:createdBy IS NULL OR created_by = :createdBy) " +
            "ORDER BY entry_date DESC, id DESC")
    List<FinanceAssetSnapshotEntity> findByGroupId(@Param("groupId") UUID groupId, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("createdBy") UUID createdBy);

    @Modifying
    @Query("UPDATE FinanceAssetSnapshotEntity s SET s.deletedAt = :now WHERE s.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceAssetSnapshotEntity s SET s.deletedAt = :now WHERE s.createdBy = :createdBy AND s.deletedAt IS NULL")
    void softDeleteByCreatedBy(@Param("createdBy") UUID createdBy, @Param("now") Instant now);

    // clearAutomatically=true 필수 — 벌크 UPDATE는 1차 캐시를 지우지 않아, 같은 트랜잭션에서 이 호출 전에
    // 이미 로드된 엔티티를 이후 findById로 다시 읽으면 groupId가 갱신 전 값으로 보인다(직접 재현 확인).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FinanceAssetSnapshotEntity s SET s.groupId = :toGroupId " +
            "WHERE s.groupId = :fromGroupId AND s.createdBy = :createdBy")
    void reassignGroup(@Param("fromGroupId") UUID fromGroupId, @Param("toGroupId") UUID toGroupId, @Param("createdBy") UUID createdBy);
}
