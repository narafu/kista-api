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

    // from/to/filterUserId는 선택적 필터 — null이면 무시. entryDate 최신순.
    // 이중축: 내 개인 스냅샷(group_id IS NULL) ∪ 내 현재 그룹 스냅샷(group_id = :currentGroupId).
    // HQL의 "(:from IS NULL OR ...)" 형태는 PostgreSQL이 IS NULL 자리 바인드 파라미터의 타입을 추론하지 못해
    // "could not determine data type of parameter"로 실패하고, HQL CAST(:from AS date)로 우회를 시도하면
    // 이번엔 Hibernate가 그 파라미터를 bytea로 바인딩해 "cannot cast type bytea to date"로 실패한다.
    // 네이티브 쿼리 + PostgreSQL ::date 캐스트로 우회한다 — JDBC가 LocalDate를 곧바로 바인딩하므로 문제없다.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_asset_snapshots WHERE deleted_at IS NULL " +
            "AND ((user_id = :userId AND group_id IS NULL) OR group_id = :currentGroupId) " +
            "AND (CAST(:from AS date) IS NULL OR entry_date >= CAST(:from AS date)) " +
            "AND (CAST(:to AS date) IS NULL OR entry_date <= CAST(:to AS date)) " +
            "AND (:filterUserId IS NULL OR user_id = :filterUserId) " +
            "ORDER BY entry_date DESC, id DESC")
    List<FinanceAssetSnapshotEntity> findMyScope(@Param("userId") UUID userId, @Param("currentGroupId") UUID currentGroupId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("filterUserId") UUID filterUserId);

    boolean existsByAccountIdAndDeletedAtIsNull(UUID accountId);

    @Modifying
    @Query("UPDATE FinanceAssetSnapshotEntity s SET s.deletedAt = :now WHERE s.id = :id")
    void softDeleteById(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE FinanceAssetSnapshotEntity s SET s.deletedAt = :now WHERE s.userId = :userId AND s.deletedAt IS NULL")
    void softDeleteByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
