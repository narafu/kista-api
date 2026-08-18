package com.kista.adapter.out.persistence.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface FinanceBudgetJpaRepository extends JpaRepository<FinanceBudgetEntity, UUID> {

    // categoryId/date는 선택적 필터 — null이면 무시. date 지정 시 그 날짜에 유효한(기간 포함, 무기한 포함) 예산만.
    // HQL의 "(:date IS NULL OR ...)" 형태는 PostgreSQL이 IS NULL 자리 바인드 파라미터의 타입을 추론하지 못해
    // "could not determine data type of parameter"로 실패하고, HQL CAST(:date AS date)로 우회를 시도하면
    // 이번엔 Hibernate가 그 파라미터를 bytea로 바인딩해 "cannot cast type bytea to date"로 실패한다.
    // 네이티브 쿼리 + PostgreSQL ::date 캐스트로 우회한다 — JDBC가 LocalDate를 곧바로 바인딩하므로 문제없다.
    @Query(nativeQuery = true, value = "SELECT * FROM finance_budgets WHERE group_id = :groupId " +
            "AND (:categoryId IS NULL OR category_id = :categoryId) " +
            "AND (CAST(:date AS date) IS NULL OR (apply_start_date <= CAST(:date AS date) " +
            "AND (apply_end_date IS NULL OR apply_end_date >= CAST(:date AS date))))")
    List<FinanceBudgetEntity> findByGroupId(@Param("groupId") UUID groupId, @Param("categoryId") UUID categoryId, @Param("date") LocalDate date);

    // 파생 설정이므로 하드 삭제 (회원 탈퇴 시)
    @Modifying
    @Query("DELETE FROM FinanceBudgetEntity b WHERE b.createdBy = :createdBy")
    void deleteByCreatedBy(@Param("createdBy") UUID createdBy);

    // clearAutomatically=true 필수 — 벌크 UPDATE는 1차 캐시를 지우지 않아, 같은 트랜잭션에서 이 호출 전에
    // 이미 로드된 엔티티를 이후 findById로 다시 읽으면 groupId가 갱신 전 값으로 보인다(직접 재현 확인).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FinanceBudgetEntity b SET b.groupId = :toGroupId " +
            "WHERE b.groupId = :fromGroupId AND b.createdBy = :createdBy")
    void reassignGroup(@Param("fromGroupId") UUID fromGroupId, @Param("toGroupId") UUID toGroupId, @Param("createdBy") UUID createdBy);
}
